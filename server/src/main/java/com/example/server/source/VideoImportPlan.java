package com.example.server.source;

import java.util.List;

/**
 * 一次 URL 解析的平台无关结果。
 *
 * <p>{@code targetType} 只能是 {@link ImportTargetType#SINGLE} 或 {@link ImportTargetType#COLLECTION}；
 * {@code containerId} 与 {@code containerTitle} 只表达组织关系，不参与媒体唯一键。
 */
public record VideoImportPlan(
        ImportTargetType targetType,
        VideoPlatform platform,
        String containerId,
        String containerTitle,
        List<VideoSourceUnit> units
) {
}
