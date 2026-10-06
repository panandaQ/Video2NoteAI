package com.example.server.dto;

import java.util.List;

/**
 * 一个原始 {@link VideoContext.VideoSegment} 的连续输入部分。
 *
 * <p>{@code segmentId} 始终指向原始时间范围；拆分后只有 {@code partNo} 和文本/时间范围变化，
 * 这样批次结果仍可回溯到同一个原始 Segment。
 */
public record SegmentPart(
        String segmentId,
        int partNo,
        long startMs,
        long endMs,
        String transcript,
        List<String> ocrTexts,
        List<String> evidenceFrames,
        TranscriptSource source,
        String chapterId
) {

    public SegmentPart {
        if (segmentId == null || segmentId.isBlank()) {
            throw new IllegalArgumentException("segmentId is required");
        }
        if (partNo < 1) throw new IllegalArgumentException("partNo must be >= 1");
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid segment part range");
        transcript = transcript == null ? "" : transcript;
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        evidenceFrames = evidenceFrames == null ? List.of() : List.copyOf(evidenceFrames);
        source = source == null ? TranscriptSource.ASR : source;
        chapterId = chapterId == null || chapterId.isBlank() ? null : chapterId.trim();
    }

    public int inputChars() {
        return transcript.length() + ocrTexts.stream().mapToInt(String::length).sum();
    }

    public static SegmentPart of(VideoContext.VideoSegment segment) {
        return of(segment, 1, segment.startMs(), segment.endMs(), segment.transcript(), segment.ocrTexts());
    }

    public static SegmentPart of(VideoContext.VideoSegment segment,
                                 int partNo,
                                 long startMs,
                                 long endMs,
                                 String transcript,
                                 List<String> ocrTexts) {
        return new SegmentPart(
                idOf(segment), partNo, startMs, endMs, transcript, ocrTexts,
                segment.evidenceFrames(), segment.source(), segment.chapterId());
    }

    public static String idOf(VideoContext.VideoSegment segment) {
        return segment.startMs() + "-" + segment.endMs();
    }
}
