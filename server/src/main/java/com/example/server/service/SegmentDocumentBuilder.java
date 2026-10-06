package com.example.server.service;

import com.example.server.config.MinuteRagProperties;
import com.example.server.dto.SegmentRetrievalDocument;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Expands the V2 context into stable segment documents without touching media artifacts. */
@Service
public class SegmentDocumentBuilder {
    private final MinuteRagProperties properties;

    public SegmentDocumentBuilder(MinuteRagProperties properties) {
        this.properties = properties;
    }

    public List<SegmentRetrievalDocument> build(Long mediaId, VideoContext context) {
        if (mediaId == null || context == null) return List.of();
        Map<String, VideoChapter> chapters = context.chapters().stream()
                .collect(Collectors.toMap(VideoChapter::id, Function.identity(), (left, right) -> left));
        String analysisVersion = context.analysisVersion() == null || context.analysisVersion().isBlank()
                ? VideoContext.ANALYSIS_VERSION_V2 : context.analysisVersion();
        return context.segments().stream()
                .filter(segment -> segment.endMs() > segment.startMs())
                .map(segment -> {
                    VideoChapter chapter = segment.chapterId() == null ? null : chapters.get(segment.chapterId());
                    return new SegmentRetrievalDocument(mediaId,
                            segmentRef(mediaId, segment), segment.chapterId(),
                            chapter == null ? null : chapter.title(), segment.startMs(), segment.endMs(),
                            preferredTranscript(segment, context.segments()), segment.source().name(), segment.ocrTexts(),
                            segment.evidenceFrames(), analysisVersion, properties.getIndexVersion(),
                            properties.getBm25Version());
                }).collect(Collectors.collectingAndThen(Collectors.toMap(
                        SegmentRetrievalDocument::segmentRef, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new), map -> List.copyOf(map.values())));
    }

    /** A segmentRef is an identity, not a score and must never inherit Chunk boundaries. */
    public String segmentRef(Long mediaId, VideoContext.VideoSegment segment) {
        return mediaId + ":" + segment.startMs() + ":" + segment.endMs();
    }

    private String preferredTranscript(VideoContext.VideoSegment segment,
                                       List<VideoContext.VideoSegment> ignored) {
        return segment.transcript();
    }
}
