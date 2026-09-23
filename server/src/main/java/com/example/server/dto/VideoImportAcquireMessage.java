package com.example.server.dto;

import java.io.Serializable;

/**
 * Unit 获取消息：只携带媒体主键和链路 ID。
 *
 * <p>不携带 {@code importId}：同一媒体可能属于多个历史导入任务，媒体状态才是获取阶段的事实来源。
 * 平台身份、规范 URL 和用户归属由消费者按 {@code mediaId} 回读 MySQL。
 */
public record VideoImportAcquireMessage(
        int version,
        Long mediaId,
        String traceId
) implements Serializable {

    public static final int CURRENT_VERSION = 1;

    public static VideoImportAcquireMessage of(Long mediaId, String traceId) {
        return new VideoImportAcquireMessage(CURRENT_VERSION, mediaId, traceId);
    }

    public boolean isSupportedVersion() {
        return version == CURRENT_VERSION;
    }
}
