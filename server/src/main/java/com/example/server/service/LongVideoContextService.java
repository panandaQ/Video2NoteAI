package com.example.server.service;

import com.example.server.config.AgentBudgetProperties;
import com.example.server.dto.AgentState;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LongVideoContextService {

    private static final long CHUNK_MS = 5 * 60 * 1000L;

    private final AgentTelemetry telemetry;
    private final MediaIndexService mediaIndexService;
    private final VideoEvidenceRetrievalService retrievalService;
    private final AgentBudgetProperties budgetProperties;

    public LongVideoContextService(AgentTelemetry telemetry,
                                   MediaIndexService mediaIndexService,
                                   VideoEvidenceRetrievalService retrievalService,
                                   AgentBudgetProperties budgetProperties) {
        this.telemetry = telemetry;
        this.mediaIndexService = mediaIndexService;
        this.retrievalService = retrievalService;
        this.budgetProperties = budgetProperties;
    }

    public VideoContext selectRelevant(VideoContext context) {
        return selectRelevant(null, context);
    }

    public VideoContext selectRelevant(Long mediaId, VideoContext context) {
        if (context.segments().isEmpty()
                || context.segments().get(context.segments().size() - 1).endMs() <= CHUNK_MS) {
            return withinBudget(context, context.segments());
        }

        List<VideoChunk> chunks = resolveChunks(mediaId, context);
        List<VideoContext.VideoSegment> selectedSegments =
                hasChapters(context)
                        ? selectPerChapter(mediaId, context, chunks)
                        : retrievalService.retrieve(mediaId, context.userGoal(), chunks);
        return withinBudget(context, selectedSegments);
    }

    public List<VideoEvidenceHit> searchEvidence(Long mediaId, VideoContext context) {
        if (context.segments().isEmpty()) return List.of();
        List<VideoChunk> chunks = resolveChunks(mediaId, context);
        return retrievalService.search(mediaId, context.userGoal(), chunks);
    }

    public VideoContext refineForCritique(Long mediaId,
                                          VideoContext fullContext,
                                          VideoContext selectedContext,
                                          AgentState.CriticResult critique) {
        Map<String, VideoContext.VideoSegment> segments = new LinkedHashMap<>();
        List<Long> requiredTimestamps = critique == null ? List.of() : critique.requiredTimestamps();
        fullContext.segments().stream()
                .filter(segment -> requiredTimestamps.stream().anyMatch(timestamp ->
                        nearSegment(timestamp, segment)))
                .forEach(segment -> segments.put(segmentKey(segment), segment));

        String critiqueQuery = critiqueQuery(fullContext.userGoal(), critique);
        VideoContext retryContext = selectRelevant(mediaId,
                new VideoContext(fullContext.source(), critiqueQuery, fullContext.segments(),
                        fullContext.durationMs(), fullContext.analysisVersion(), fullContext.chapters()));
        retryContext.segments().forEach(segment -> segments.putIfAbsent(segmentKey(segment), segment));
        selectedContext.segments().forEach(segment -> segments.putIfAbsent(segmentKey(segment), segment));
        return withinBudget(fullContext, new ArrayList<>(segments.values()));
    }

    private String critiqueQuery(String goal, AgentState.CriticResult critique) {
        if (critique == null) return goal;
        return String.join("\n",
                goal,
                String.join(" ", critique.feedback() == null ? List.of() : critique.feedback()),
                String.join(" ", critique.missingRequirements() == null ? List.of() : critique.missingRequirements()),
                String.join(" ", critique.unsupportedClaims() == null ? List.of() : critique.unsupportedClaims()));
    }

    private boolean hasChapters(VideoContext context) {
        return context.chapters() != null && !context.chapters().isEmpty();
    }

    /**
     * 逐章检索（计划 §5.3）：每章独立检索本章 Chunk 并保证至少一个候选（最低配额），
     * 避免全局 top-K 让弱相关章节饿死；未分章素材单独检索参与全局结论。
     */
    private List<VideoContext.VideoSegment> selectPerChapter(Long mediaId,
                                                             VideoContext context,
                                                             List<VideoChunk> chunks) {
        Map<String, VideoContext.VideoSegment> selected = new LinkedHashMap<>();
        for (VideoChapter chapter : context.chapters()) {
            List<VideoChunk> chapterChunks = chunks.stream()
                    .filter(chunk -> chapter.id().equals(chunk.chapterId()))
                    .toList();
            if (chapterChunks.isEmpty()) continue;
            List<VideoContext.VideoSegment> chapterHits =
                    retrievalService.retrieve(mediaId, context.userGoal(), chapterChunks, chapter.id());
            if (chapterHits.isEmpty()) {
                chapterHits = chapterChunks.stream()
                        .flatMap(chunk -> chunk.rawSegments().stream())
                        .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                        .limit(1)
                        .toList();
            }
            chapterHits.forEach(segment -> selected.putIfAbsent(segmentKey(segment), segment));
        }
        List<VideoChunk> unassigned = chunks.stream()
                .filter(chunk -> chunk.chapterId() == null)
                .toList();
        if (!unassigned.isEmpty()) {
            retrievalService.retrieve(mediaId, context.userGoal(), unassigned)
                    .forEach(segment -> selected.putIfAbsent(segmentKey(segment), segment));
        }
        return new ArrayList<>(selected.values());
    }

    private String segmentKey(VideoContext.VideoSegment segment) {
        return segment.startMs() + ":" + segment.endMs();
    }

    private VideoContext withinBudget(VideoContext context,
                                      List<VideoContext.VideoSegment> candidates) {
        List<VideoContext.VideoSegment> selected = new ArrayList<>();
        int usedChars = 0;
        for (VideoContext.VideoSegment segment : candidates) {
            int segmentChars = segment.transcript().length()
                    + segment.ocrTexts().stream().mapToInt(String::length).sum();
            if (!selected.isEmpty() && usedChars + segmentChars > budgetProperties.getContextMaxChars()) continue;
            selected.add(segment);
            usedChars += segmentChars;
        }
        telemetry.incrementCurrent("contextSegmentsDropped", candidates.size() - selected.size());
        telemetry.valueCurrent("contextChars", usedChars);
        selected.sort(Comparator.comparingLong(VideoContext.VideoSegment::startMs));
        // 保留 V2 元数据：裁剪只选片段，不能把时长/版本/章节裁丢（交付约束 2）
        return new VideoContext(context.source(), context.userGoal(), selected,
                context.durationMs(), context.analysisVersion(), context.chapters());
    }

    private boolean nearSegment(long timestamp, VideoContext.VideoSegment segment) {
        long margin = Math.max(60_000L, segment.endMs() - segment.startMs());
        return timestamp >= Math.max(0, segment.startMs() - margin)
                && timestamp < segment.endMs() + margin;
    }

    private List<VideoChunk> resolveChunks(Long mediaId,
                                           VideoContext context) {
        // 构建 / 落盘 / 写索引统一由 MediaIndexService 承担：导入链路也要判断"索引是否就绪"，
        // 两处各写一份迟早会出现"某条路径没写索引却显示完成"（D-069）。
        MediaIndexService.ChunkSnapshot snapshot =
                mediaIndexService.ensureChunks(mediaId, context);
        if (snapshot.reused()) {
            telemetry.incrementCurrent("chunkCheckpointHits", 1);
        }
        return snapshot.chunks();
    }

}
