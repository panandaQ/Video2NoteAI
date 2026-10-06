package com.example.server.dto;

import java.util.List;

/**
 * One chunk's retrieval-stage diagnostics. Raw lexical and dense scores are
 * intentionally kept separate from rank-only RRF so Bad Case analysis can
 * identify the stage that lost the evidence.
 */
public record RetrievalScoreBreakdown(
        Double bm25Score,
        Integer bm25Rank,
        Double denseScore,
        Integer denseRank,
        Double rrfScore,
        Double rerankerScore,
        Integer rerankerRank,
        Double finalScore,
        boolean rerankerApplied,
        boolean rerankerFallback,
        List<String> channels
) {
    public RetrievalScoreBreakdown {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    public static RetrievalScoreBreakdown unknown(double finalScore) {
        return new RetrievalScoreBreakdown(
                null, null, null, null, null, null, null, finalScore,
                false, false, List.of());
    }
}
