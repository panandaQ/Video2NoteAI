package com.example.server.dto;

import java.util.List;

/**
 * 默认笔记 Executor 的单次输入批次。
 *
 * <p>该对象只在进程内传递，不进入 RocketMQ；批次中的原文由已有 VideoContext/Checkpoint 提供。
 */
public record NoteInputBatch(
        Long mediaId,
        String batchId,
        String chapterId,
        String chapterTitle,
        int sequenceNo,
        long startMs,
        long endMs,
        List<SegmentPart> segments,
        long inputChars,
        long estimatedInputTokens,
        boolean truncated
) {

    public NoteInputBatch {
        if (mediaId == null) throw new IllegalArgumentException("mediaId is required");
        if (batchId == null || batchId.isBlank()) throw new IllegalArgumentException("batchId is required");
        if (sequenceNo < 1) throw new IllegalArgumentException("sequenceNo must be >= 1");
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid batch range");
        segments = segments == null ? List.of() : List.copyOf(segments);
        if (segments.isEmpty()) throw new IllegalArgumentException("batch must contain at least one segment");
        if (inputChars < 0 || estimatedInputTokens < 0) {
            throw new IllegalArgumentException("batch size cannot be negative");
        }
        chapterId = chapterId == null || chapterId.isBlank() ? null : chapterId.trim();
        chapterTitle = chapterTitle == null ? "" : chapterTitle.trim();
    }

    public static String stableBatchId(Long mediaId,
                                       String profileVersion,
                                       String chapterId,
                                       int sequenceNo) {
        if (mediaId == null) throw new IllegalArgumentException("mediaId is required");
        if (sequenceNo < 1) throw new IllegalArgumentException("sequenceNo must be >= 1");
        if (profileVersion == null || profileVersion.isBlank()) {
            throw new IllegalArgumentException("profileVersion is required");
        }
        String chapterKey = chapterId == null || chapterId.isBlank() ? "no-chapter" : chapterId.trim();
        return "note:" + mediaId + ":" + profileVersion.trim() + ":" + chapterKey + ":"
                + String.format("%04d", sequenceNo);
    }
}
