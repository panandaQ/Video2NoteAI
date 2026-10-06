package com.example.server.dto;

/**
 * 一个默认笔记输入批次的可恢复 Executor 结果。
 *
 * <p>结果键明确包含媒体、笔记版本和稳定 batchId；因此 MQ/恢复重投只会覆盖同一批次的
 * 结果，不会把 V1/V2 或不同媒体的产物混在一起。
 */
public record NoteBatchResult(
        Long mediaId,
        String profileVersion,
        String batchId,
        int sequenceNo,
        AnalysisResult result,
        int attempts,
        boolean succeeded,
        String error
) {
    public NoteBatchResult {
        if (mediaId == null) throw new IllegalArgumentException("mediaId is required");
        if (profileVersion == null || profileVersion.isBlank()) {
            throw new IllegalArgumentException("profileVersion is required");
        }
        if (batchId == null || batchId.isBlank()) throw new IllegalArgumentException("batchId is required");
        if (sequenceNo < 1) throw new IllegalArgumentException("sequenceNo must be >= 1");
        if (attempts < 0) throw new IllegalArgumentException("attempts cannot be negative");
        if (succeeded && result == null) throw new IllegalArgumentException("successful batch needs result");
        error = error == null ? "" : error.trim();
    }

    public String idempotencyKey() {
        return idempotencyKey(mediaId, profileVersion, batchId);
    }

    public static String idempotencyKey(Long mediaId, String profileVersion, String batchId) {
        if (mediaId == null || profileVersion == null || profileVersion.isBlank()
                || batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("mediaId, profileVersion and batchId are required");
        }
        return mediaId + ":" + profileVersion.trim() + ":" + batchId.trim();
    }
}
