package com.example.server.dto;

/**
 * 转录来源（计划 §4.5）：{@code CC} = 平台字幕，{@code ASR} = 语音识别兜底。
 * 证据 source 字段对外可见（{@code CC/CC+OCR/ASR/ASR+OCR}），便于审计字幕质量。
 */
public enum TranscriptSource {
    CC,
    ASR
}
