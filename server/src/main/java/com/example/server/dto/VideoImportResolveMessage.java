package com.example.server.dto;

import java.io.Serializable;

/**
 * URL 解析消息：只携带稳定主键和链路 ID。
 *
 * <p>原始 URL、用户 ID 和容器信息全部由消费者回读 MySQL，消息内容不作为权限或业务事实。
 * 禁止把媒体内容、字幕、临时路径、Cookie、Token 或签名地址放进消息。
 */
public record VideoImportResolveMessage(
        int version,
        Long importId,
        String traceId
) implements Serializable {

    public static final int CURRENT_VERSION = 1;

    public static VideoImportResolveMessage of(Long importId, String traceId) {
        return new VideoImportResolveMessage(CURRENT_VERSION, importId, traceId);
    }

    public boolean isSupportedVersion() {
        return version == CURRENT_VERSION;
    }
}
