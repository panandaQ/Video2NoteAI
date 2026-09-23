package com.example.server.service;

import com.example.server.dto.ChunkCleanResult;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 将长视频整理成可持久化、可检索的五分钟知识块。
 *
 * <p>V2 语义（计划 §5.1）：先按 {@code chapterId} 分组，再在章内按最长五分钟切 Chunk，
 * 一个 Chunk 不得跨两个 View 章节；未分章素材单独成组参与全局结论。Chunk 携带
 * {@code analysisVersion} 与章节归属字段，随 Qdrant payload 同步写入。
 *
 * <p>语义清洗（批量）：切块后不逐块摘要，而是按 5 块一批调用 {@link DeepSeekUtils#cleanChunks}，
 * 让模型结合批内前后文做同音/形近/专有名词纠错；纠错后的规范检索词写入 {@code keywords}，
 * 原始表达作为检索别名写入 {@code originalTerms}。单批失败只降级该批（原文前 500 字），不中断整体构建。
 */
@Service
public class VideoChunkingService {

    private static final long CHUNK_MS = 5 * 60 * 1000L;
    private static final int CLEAN_BATCH_SIZE = 5;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingUtils embeddingUtils;
    private final AgentTelemetry telemetry;

    public VideoChunkingService(DeepSeekUtils deepSeekUtils,
                                EmbeddingUtils embeddingUtils,
                                AgentTelemetry telemetry) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.telemetry = telemetry;
    }

    public List<VideoChunk> build(List<VideoContext.VideoSegment> segments) {
        return build(segments, List.of(), VideoContext.ANALYSIS_VERSION_V2);
    }

    public List<VideoChunk> build(List<VideoContext.VideoSegment> segments, List<VideoChapter> chapters) {
        return build(segments, chapters, VideoContext.ANALYSIS_VERSION_V2);
    }

    /**
     * @param analysisVersion 分块所属的处理版本；{@code null} 表示旧上下文只读复用产物（D-079：
     *                       不带版本标记的点不会被 V2 查询命中）
     */
    public List<VideoChunk> build(List<VideoContext.VideoSegment> segments,
                                  List<VideoChapter> chapters,
                                  String analysisVersion) {
        if (segments == null || segments.isEmpty()) return List.of();

        List<VideoContext.VideoSegment> orderedSegments = segments.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .toList();
        if (orderedSegments.isEmpty()) return List.of();

        Map<String, List<VideoContext.VideoSegment>> byChapter = new LinkedHashMap<>();
        for (VideoContext.VideoSegment segment : orderedSegments) {
            byChapter.computeIfAbsent(segment.chapterId(), key -> new ArrayList<>()).add(segment);
        }
        Map<String, VideoChapter> chapterById = chapters == null ? Map.of()
                : chapters.stream().collect(Collectors.toMap(VideoChapter::id, chapter -> chapter));

        List<VideoChunk> rawChunks = new ArrayList<>();
        // 章节组按平台顺序先出，未分章素材（chapterId=null）最后。
        List<String> groupOrder = new ArrayList<>();
        if (chapters != null) {
            chapters.forEach(chapter -> groupOrder.add(chapter.id()));
        }
        groupOrder.addAll(byChapter.keySet().stream()
                .filter(id -> !groupOrder.contains(id))
                .toList());
        for (String chapterId : groupOrder) {
            rawChunks.addAll(chunkGroup(chapterId, byChapter.get(chapterId),
                    chapterById.get(chapterId), analysisVersion));
        }
        return cleanAndEmbed(rawChunks);
    }

    /** 章内切块，只产出原始块（不调模型）；摘要/关键词/别名/向量在 {@link #cleanAndEmbed} 统一回填。 */
    private List<VideoChunk> chunkGroup(String chapterId,
                                        List<VideoContext.VideoSegment> groupSegments,
                                        VideoChapter chapter,
                                        String analysisVersion) {
        List<VideoChunk> chunks = new ArrayList<>();
        long groupStart = chapter == null
                ? groupSegments.get(0).startMs()
                : Math.min(chapter.startMs(), groupSegments.get(0).startMs());
        long groupEnd = chapter == null
                ? groupSegments.get(groupSegments.size() - 1).endMs()
                : Math.max(chapter.endMs(), groupSegments.get(groupSegments.size() - 1).endMs());
        for (long start = groupStart; start < groupEnd; start += CHUNK_MS) {
            final long chunkStart = start;
            long end = Math.min(start + CHUNK_MS, groupEnd);
            List<VideoContext.VideoSegment> rawSegments = groupSegments.stream()
                    .filter(segment -> segment.startMs() >= chunkStart && segment.startMs() < end)
                    .toList();
            if (rawSegments.isEmpty()) continue;

            chunks.add(new VideoChunk(
                    start, end, "", List.of(), List.of(), List.of(), rawSegments, List.of(),
                    analysisVersion, chapterId,
                    chapter == null ? null : chapter.title(),
                    chapter == null ? null : chapter.startMs(),
                    chapter == null ? null : chapter.endMs()));
        }
        return chunks;
    }

    private List<VideoChunk> cleanAndEmbed(List<VideoChunk> rawChunks) {
        List<VideoChunk> cleaned = new ArrayList<>();
        for (int i = 0; i < rawChunks.size(); i += CLEAN_BATCH_SIZE) {
            List<VideoChunk> batch = rawChunks.subList(i, Math.min(i + CLEAN_BATCH_SIZE, rawChunks.size()));
            cleaned.addAll(cleanBatch(batch));
        }
        return cleaned;
    }

    private List<VideoChunk> cleanBatch(List<VideoChunk> batch) {
        List<VideoChunk> cleaned;
        try {
            Map<String, ChunkCleanResult> byId = deepSeekUtils.cleanChunks(batch).stream()
                    .collect(Collectors.toMap(ChunkCleanResult::chunkId, result -> result, (a, b) -> a));
            cleaned = new ArrayList<>();
            for (VideoChunk raw : batch) {
                ChunkCleanResult result = byId.get(String.valueOf(raw.startTime()));
                cleaned.add(result == null ? fallbackChunk(raw) : assemble(raw, result));
            }
        } catch (RuntimeException e) {
            cleaned = batch.stream().map(this::fallbackChunk).toList();
        }
        return cleaned;
    }

    private VideoChunk assemble(VideoChunk raw, ChunkCleanResult result) {
        List<String> keywords = normalizeTexts(result.normalizedTerms());
        List<String> originalTerms = normalizeTexts(result.originalTerms());
        String embeddingText = result.summary() + "\n" + String.join(" ", keywords);
        return new VideoChunk(
                raw.startTime(), raw.endTime(),
                result.summary(), keywords, originalTerms, result.corrections(), raw.rawSegments(),
                embed(embeddingText),
                raw.analysisVersion(), raw.chapterId(), raw.chapterTitle(),
                raw.chapterStartMs(), raw.chapterEndMs());
    }

    private VideoChunk fallbackChunk(VideoChunk raw) {
        telemetry.incrementCurrent("summaryFallbacks", 1);
        String rawText = raw.rawSegments().stream()
                .map(segment -> segment.transcript() + " "
                        + String.join(" ", normalizeTexts(segment.ocrTexts())))
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining(" "));
        String summary = rawText.length() <= 500 ? rawText : rawText.substring(0, 500);
        return new VideoChunk(
                raw.startTime(), raw.endTime(),
                summary, List.of(), List.of(), List.of(), raw.rawSegments(),
                embed(summary),
                raw.analysisVersion(), raw.chapterId(), raw.chapterTitle(),
                raw.chapterStartMs(), raw.chapterEndMs());
    }

    private List<Double> embed(String text) {
        try {
            return embeddingUtils.embed(text);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("chunkEmbeddingFallbacks", 1);
            return List.of();
        }
    }

    private List<String> normalizeTexts(List<String> texts) {
        return texts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .toList();
    }
}
