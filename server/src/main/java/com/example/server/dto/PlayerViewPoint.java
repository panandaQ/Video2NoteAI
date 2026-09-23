package com.example.server.dto;

/**
 * player API 返回的原始 View 章节条目（时间单位为秒，与 `view_points[]` 的 content/from/to/type 对应）。
 * 规范化由 {@code ChapterNormalizer} 完成。
 */
public record PlayerViewPoint(String content, double fromSec, double toSec, Integer type) {
}
