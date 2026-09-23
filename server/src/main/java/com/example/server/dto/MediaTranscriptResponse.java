package com.example.server.dto;

import java.util.List;

/** 当前用户所拥有媒体的完整时间轴字幕。 */
public record MediaTranscriptResponse(
        Long mediaId,
        boolean available,
        List<Segment> segments
) {
    public MediaTranscriptResponse {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public record Segment(
            long startMs,
            long endMs,
            String text,
            String source,
            String chapterId
    ) {
    }
}
