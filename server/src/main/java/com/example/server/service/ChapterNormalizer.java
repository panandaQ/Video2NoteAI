package com.example.server.service;

import com.example.server.dto.PlayerViewPoint;
import com.example.server.dto.VideoChapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 player API 的 {@code view_points} 规范化为领域章节（计划 §5.1，D-074）。
 *
 * <p>规则：保留返回顺序；标题非空、时间是有限数值、{@code 0 <= start < end <= durationMs}；
 * 生成稳定 ID {@code vp-{序号}-{startMs}}；章节间允许留白（未分章素材），但乱序、重叠、
 * 大幅越界或无法转换的条目判整份无效——调用方重试 2 次后显式记录
 * {@code chapters.status=INVALID} 并按无章节继续，不静默丢章（D-074）。
 */
public final class ChapterNormalizer {

    /** {@code to} 秒级四舍五入导致的小幅越界容差：超出不超过该值则 clamp 到视频时长（D-074）。 */
    public static final long OVERRUN_TOLERANCE_MS = 2_000L;

    public static final String SOURCE_BILIBILI_VIEW_POINT = "BILIBILI_VIEW_POINT";

    private ChapterNormalizer() {
    }

    public static List<VideoChapter> normalize(List<PlayerViewPoint> points, long durationMs) {
        if (points == null || points.isEmpty()) return List.of();
        if (durationMs < 0) throw new IllegalArgumentException("durationMs must be >= 0");

        List<VideoChapter> chapters = new ArrayList<>(points.size());
        long previousEndMs = -1;
        for (int i = 0; i < points.size(); i++) {
            PlayerViewPoint point = points.get(i);
            if (point.content() == null || point.content().isBlank()) {
                throw new InvalidChapterDataException("章节标题为空 index=" + i);
            }
            if (!Double.isFinite(point.fromSec()) || !Double.isFinite(point.toSec())) {
                throw new InvalidChapterDataException("章节时间不是有限数值 index=" + i);
            }
            long startMs = Math.round(point.fromSec() * 1000.0);
            long endMs = Math.round(point.toSec() * 1000.0);
            if (startMs < 0 || endMs <= startMs) {
                throw new InvalidChapterDataException("章节时间范围非法 index=" + i);
            }
            // 同时捕获乱序（整段在前）与重叠（与前段相交）。
            if (startMs < previousEndMs) {
                throw new InvalidChapterDataException("章节乱序或重叠 index=" + i);
            }
            if (endMs > durationMs) {
                if (endMs - durationMs <= OVERRUN_TOLERANCE_MS) {
                    endMs = durationMs;
                } else {
                    throw new InvalidChapterDataException("章节越界 index=" + i);
                }
            }
            previousEndMs = endMs;
            chapters.add(new VideoChapter(
                    "vp-" + i + "-" + startMs,
                    point.content().trim(),
                    startMs,
                    endMs,
                    point.type(),
                    SOURCE_BILIBILI_VIEW_POINT));
        }
        return List.copyOf(chapters);
    }

    public static class InvalidChapterDataException extends IllegalArgumentException {
        public InvalidChapterDataException(String message) {
            super(message);
        }
    }
}
