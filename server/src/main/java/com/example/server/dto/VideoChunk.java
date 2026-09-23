package com.example.server.dto;

import java.util.List;

/**
 * 长视频的五分钟语义块：摘要用于检索，原始片段用于命中后按需装载。
 *
 * <p>V2 扩展（计划 §5.1）：携带 {@code analysisVersion} 与章节归属（chapterId/chapterTitle/
 * chapterStartMs/chapterEndMs），一个 Chunk 不得跨两个 View 章节。旧 JSON 缺省时反序列化为空。
 *
 * <p>语义清洗扩展：{@code keywords} 的语义升级为「规范检索词」（清洗提示词的 normalizedTerms）；
 * {@code originalTerms} 保留需要召回的原始表达（含可能的同音/形近/专有名词转写错误）作为检索别名，
 * 只参与关键词召回、不进 embedding；{@code corrections} 是高置信纠错记录，仅用于审计/评测。
 */
public record VideoChunk(
        long startTime,
        long endTime,
        String segmentSummary,
        List<String> keywords,
        List<String> originalTerms,
        List<ChunkCleanResult.ChunkCorrection> corrections,
        List<VideoContext.VideoSegment> rawSegments,
        List<Double> embedding,
        String analysisVersion,
        String chapterId,
        String chapterTitle,
        Long chapterStartMs,
        Long chapterEndMs
) {
    public VideoChunk {
        if (startTime < 0 || endTime <= startTime) throw new IllegalArgumentException("invalid chunk range");
        segmentSummary = segmentSummary == null ? "" : segmentSummary.trim();
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        originalTerms = originalTerms == null ? List.of() : List.copyOf(originalTerms);
        corrections = corrections == null ? List.of() : List.copyOf(corrections);
        rawSegments = rawSegments == null ? List.of() : List.copyOf(rawSegments);
        embedding = embedding == null ? List.of() : List.copyOf(embedding);
        chapterId = chapterId == null || chapterId.isBlank() ? null : chapterId.trim();
        chapterTitle = chapterTitle == null || chapterTitle.isBlank() ? null : chapterTitle.trim();
    }

    /** 兼容旧构造：无版本、章节与语义清洗字段。 */
    public VideoChunk(long startTime, long endTime, String segmentSummary,
                      List<String> keywords, List<VideoContext.VideoSegment> rawSegments,
                      List<Double> embedding) {
        this(startTime, endTime, segmentSummary, keywords, List.of(), List.of(), rawSegments, embedding,
                null, null, null, null, null);
    }

    /** 兼容旧构造：无语义清洗字段（originalTerms/corrections 缺省为空）。 */
    public VideoChunk(long startTime, long endTime, String segmentSummary,
                      List<String> keywords, List<VideoContext.VideoSegment> rawSegments,
                      List<Double> embedding, String analysisVersion,
                      String chapterId, String chapterTitle,
                      Long chapterStartMs, Long chapterEndMs) {
        this(startTime, endTime, segmentSummary, keywords, List.of(), List.of(), rawSegments, embedding,
                analysisVersion, chapterId, chapterTitle, chapterStartMs, chapterEndMs);
    }

    public long startMs() {
        return startTime;
    }

    public long endMs() {
        return endTime;
    }
}
