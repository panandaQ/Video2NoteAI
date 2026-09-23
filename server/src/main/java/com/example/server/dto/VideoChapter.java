package com.example.server.dto;

/**
 * 平台 View 章节的领域对象（计划 §5.1）。
 *
 * <p>{@code id} 是稳定身份（{@code vp-{序号}-{startMs}}），标题只用于展示，不作为身份；
 * 时间统一毫秒；{@code source} 记录章节来源（首期为 {@code BILIBILI_VIEW_POINT}）。
 */
public record VideoChapter(
        String id,
        String title,
        long startMs,
        long endMs,
        Integer platformType,
        String source
) {
    public VideoChapter {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("chapter id is required");
        title = title == null ? "" : title.trim();
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid chapter range");
        source = source == null ? "" : source.trim();
    }
}
