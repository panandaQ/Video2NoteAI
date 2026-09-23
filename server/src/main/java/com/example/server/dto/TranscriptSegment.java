package com.example.server.dto;

/**
 * 一段带时间轴的转录文本。旧 JSON 缺省 {@code source} 时按 {@link TranscriptSource#ASR} 兼容。
 */
public record TranscriptSegment(long startMs, long endMs, String text, TranscriptSource source) {

    public TranscriptSegment {
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid transcript range");
        text = text == null ? "" : text.trim();
        source = source == null ? TranscriptSource.ASR : source;
    }

    /** 兼容旧构造：默认 ASR 来源。 */
    public TranscriptSegment(long startMs, long endMs, String text) {
        this(startMs, endMs, text, TranscriptSource.ASR);
    }
}
