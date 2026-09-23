package com.example.server.dto.knowledge;

import java.util.Locale;

/**
 * 单轮回答的来源模式（runbook §6.2 三模式）。
 *
 * <p>把旧的单一布尔 {@code answerable} 解耦为来源维度：回答由视频证据支撑的程度。
 * 无论哪种模式，系统都会给出回答，只是来源不同——不存在"拒答"终态。
 * <ul>
 *   <li>{@code VIDEO_GROUNDED}：完全由视频证据支持；</li>
 *   <li>{@code HYBRID}：一部分来自视频，一部分来自模型内部知识；</li>
 *   <li>{@code MODEL_KNOWLEDGE}：本轮未检索到足够视频证据，答案来自模型内部知识。</li>
 * </ul>
 */
public enum AnswerMode {
    VIDEO_GROUNDED,
    HYBRID,
    MODEL_KNOWLEDGE;

    /**
     * 解析模型输出的模式字符串。未知/空值兜底为 {@link #MODEL_KNOWLEDGE}：
     * 结构非法时不声称有视频依据，是最保守、最不会伪装视频内容的处置。
     */
    public static AnswerMode parse(String raw) {
        if (raw == null) {
            return MODEL_KNOWLEDGE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (AnswerMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return MODEL_KNOWLEDGE;
    }
}
