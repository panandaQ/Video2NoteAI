package com.example.server.dto;

import java.util.List;

/**
 * 用户可直接跳转和核验的视频证据。
 *
 * <p>{@code score} 是检索阶段的融合打分（semantic/keyword/visual 加权），仅用于诊断与评测，
 * 不进入回答 Prompt；0.0 表示未打分（旧调用方或降级路径）。
 */
public record VideoEvidenceHit(
        long startMs,
        long endMs,
        String source,
        String snippet,
        String transcript,
        List<String> ocrTexts,
        double score
) {
    public VideoEvidenceHit {
        source = source == null ? "" : source;
        snippet = snippet == null ? "" : snippet;
        transcript = transcript == null ? "" : transcript;
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
    }

    /** 兼容旧构造：无分数（0.0）。 */
    public VideoEvidenceHit(long startMs, long endMs, String source, String snippet,
                            String transcript, List<String> ocrTexts) {
        this(startMs, endMs, source, snippet, transcript, ocrTexts, 0.0);
    }
}
