package com.example.server.dto;

import java.util.List;

/**
 * 用户可直接跳转和核验的视频证据。
 *
 * <p>{@code score} 是检索阶段的 RRF/Reranker 分数或降级路径分数，仅用于诊断与评测，
 * 不进入回答 Prompt；0.0 表示未打分（旧调用方或降级路径）。
 */
public record VideoEvidenceHit(
        long startMs,
        long endMs,
        String source,
        String snippet,
        String transcript,
        List<String> ocrTexts,
        double score,
        String chunkRef,
        List<String> evidenceFrames,
        String transcriptSource,
        RetrievalScoreBreakdown scoreBreakdown
) {
    public VideoEvidenceHit {
        source = source == null ? "" : source;
        snippet = snippet == null ? "" : snippet;
        transcript = transcript == null ? "" : transcript;
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        chunkRef = chunkRef == null ? "" : chunkRef;
        evidenceFrames = evidenceFrames == null ? List.of() : List.copyOf(evidenceFrames);
        transcriptSource = transcriptSource == null || transcriptSource.isBlank()
                ? "NONE" : transcriptSource;
        scoreBreakdown = scoreBreakdown == null
                ? RetrievalScoreBreakdown.unknown(score) : scoreBreakdown;
    }

    /** 兼容旧构造：无分数（0.0）。 */
    public VideoEvidenceHit(long startMs, long endMs, String source, String snippet,
                            String transcript, List<String> ocrTexts) {
        this(startMs, endMs, source, snippet, transcript, ocrTexts, 0.0, "", List.of(),
                "UNKNOWN", RetrievalScoreBreakdown.unknown(0.0));
    }

    public VideoEvidenceHit(long startMs, long endMs, String source, String snippet,
                            String transcript, List<String> ocrTexts, double score) {
        this(startMs, endMs, source, snippet, transcript, ocrTexts, score, "", List.of(),
                "UNKNOWN", RetrievalScoreBreakdown.unknown(score));

    }

    public VideoEvidenceHit(long startMs, long endMs, String source, String snippet,
                            String transcript, List<String> ocrTexts, double score,
                            String chunkRef, List<String> evidenceFrames) {
        this(startMs, endMs, source, snippet, transcript, ocrTexts, score,
                chunkRef, evidenceFrames, "UNKNOWN", RetrievalScoreBreakdown.unknown(score));
    }

}
