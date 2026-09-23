package com.example.server.evaluation.runner;

import com.example.server.dto.knowledge.AnswerMode;
import com.example.server.dto.knowledge.AnswerOutcome;
import com.example.server.entity.KnowledgeTurnEvidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 八项质量指标的逐轮值；当前执行器不暴露的数据明确为 null。 */
public record EvaluationMetrics(
        Double rewriteAccuracy,
        Double hitAt5,
        Double recallAt5,
        Double mrr,
        Double answerKeyPointRecall,
        Double citationPrecision,
        Double timestampOverlapRate,
        Double unsupportedClaimRate,
        Map<String, String> unavailableReasons
) {
    public EvaluationMetrics {
        unavailableReasons = unavailableReasons == null ? Map.of() : Map.copyOf(unavailableReasons);
    }

    public static EvaluationMetrics calculate(EvaluationDataset.GoldenTurn gold,
                                               AnswerOutcome actual,
                                               EvaluationVariant variant,
                                               RetrievalProbe.ProbeResult retrievalProbe,
                                               String retrievalProbeUnavailableReason,
                                               ClaimJudge.Judgement claimJudgement,
                                               String claimJudgeUnavailableReason) {
        Map<String, String> unavailable = new LinkedHashMap<>();
        Double rewrite = null;
        if (variant.options().rewriteQuery() && gold.turnNo() > 1) {
            rewrite = normalized(actual.rewrittenQuery()).equals(normalized(gold.standaloneQuestion()))
                    ? 1D : 0D;
        } else {
            unavailable.put("rewriteAccuracy", "Query rewrite is disabled or not applicable to the first turn");
        }

        Double hitAt5 = null;
        Double recallAt5 = null;
        Double mrr = null;
        if (gold.goldEvidence().isEmpty()) {
            unavailable.put("hitAt5", "Gold turn has no evidence intervals");
            unavailable.put("recallAt5", "Gold turn has no evidence intervals");
            unavailable.put("mrr", "Gold turn has no evidence intervals");
        } else if (retrievalProbe == null) {
            String reason = retrievalProbeUnavailableReason == null
                    || retrievalProbeUnavailableReason.isBlank()
                    ? "RetrievalProbe did not produce ranked Top-5 hits"
                    : retrievalProbeUnavailableReason;
            unavailable.put("hitAt5", reason);
            unavailable.put("recallAt5", reason);
            unavailable.put("mrr", reason);
        } else {
            List<com.example.server.dto.VideoEvidenceHit> ranked = retrievalProbe.rankedTop5();
            int firstRelevantRank = 0;
            for (int i = 0; i < ranked.size(); i++) {
                if (overlapsAny(ranked.get(i).startMs(), ranked.get(i).endMs(), gold.goldEvidence())) {
                    firstRelevantRank = i + 1;
                    break;
                }
            }
            hitAt5 = firstRelevantRank == 0 ? 0D : 1D;
            mrr = firstRelevantRank == 0 ? 0D : 1D / firstRelevantRank;
            long recalledIntervals = gold.goldEvidence().stream()
                    .filter(expected -> ranked.stream().anyMatch(hit ->
                            overlaps(hit.startMs(), hit.endMs(), expected.startMs(), expected.endMs())))
                    .count();
            recallAt5 = (double) recalledIntervals / gold.goldEvidence().size();
        }

        Double keyPointRecall = keyPointRecall(gold, actual.answer());
        if (keyPointRecall == null) {
            unavailable.put("answerKeyPointRecall", "Gold turn has no answer key points");
        }

        Double citationPrecision = null;
        Double timestampOverlap = null;
        if (!variant.options().groundWithEvidence()) {
            unavailable.put("citationPrecision", "Evidence grounding is disabled for this ablation variant");
            unavailable.put("timestampOverlapRate", "Evidence grounding is disabled for this ablation variant");
        } else if (actual.evidence().isEmpty()) {
            unavailable.put("citationPrecision", "The answer has no verified citations");
            unavailable.put("timestampOverlapRate", "The answer has no verified citations");
        } else if (gold.goldEvidence().isEmpty()) {
            unavailable.put("citationPrecision", "Gold turn has no evidence intervals");
            unavailable.put("timestampOverlapRate", "Gold turn has no evidence intervals");
        } else {
            citationPrecision = citationPrecision(actual.evidence(), gold.goldEvidence());
            timestampOverlap = timestampOverlap(actual.evidence(), gold.goldEvidence());
        }
        Double unsupportedClaimRate = null;
        if (actual.answerMode() == AnswerMode.MODEL_KNOWLEDGE) {
            unavailable.put("unsupportedClaimRate",
                    "Actual output is MODEL_KNOWLEDGE and carries no video-attributed claims");
        } else if (claimJudgement == null) {
            unavailable.put("unsupportedClaimRate",
                    claimJudgeUnavailableReason == null || claimJudgeUnavailableReason.isBlank()
                            ? "ClaimJudge is unavailable" : claimJudgeUnavailableReason);
        } else {
            unsupportedClaimRate = claimJudgement.unsupportedClaimRate();
        }

        return new EvaluationMetrics(rewrite, hitAt5, recallAt5, mrr, keyPointRecall,
                citationPrecision, timestampOverlap, unsupportedClaimRate, unavailable);
    }

    public static EvaluationMetrics unavailable(String reason) {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String name : metricNames()) reasons.put(name, reason);
        return new EvaluationMetrics(null, null, null, null, null, null, null, null, reasons);
    }

    public static List<String> metricNames() {
        return List.of("rewriteAccuracy", "hitAt5", "recallAt5", "mrr",
                "answerKeyPointRecall", "citationPrecision", "timestampOverlapRate",
                "unsupportedClaimRate");
    }

    public Double value(String name) {
        return switch (name) {
            case "rewriteAccuracy" -> rewriteAccuracy;
            case "hitAt5" -> hitAt5;
            case "recallAt5" -> recallAt5;
            case "mrr" -> mrr;
            case "answerKeyPointRecall" -> answerKeyPointRecall;
            case "citationPrecision" -> citationPrecision;
            case "timestampOverlapRate" -> timestampOverlapRate;
            case "unsupportedClaimRate" -> unsupportedClaimRate;
            default -> throw new IllegalArgumentException("unknown metric: " + name);
        };
    }

    private static Double keyPointRecall(EvaluationDataset.GoldenTurn gold, String answer) {
        if (gold.answerKeyPoints().isEmpty()) return null;
        String normalizedAnswer = normalized(answer);
        long matched = gold.answerKeyPoints().stream()
                .map(EvaluationMetrics::normalized)
                .filter(point -> !point.isEmpty() && normalizedAnswer.contains(point))
                .count();
        return (double) matched / gold.answerKeyPoints().size();
    }

    private static double citationPrecision(List<KnowledgeTurnEvidence> citations,
                                            List<EvaluationDataset.GoldEvidence> gold) {
        long hits = citations.stream()
                .filter(citation -> gold.stream().anyMatch(expected -> overlaps(citation, expected)))
                .count();
        return (double) hits / citations.size();
    }

    private static double timestampOverlap(List<KnowledgeTurnEvidence> citations,
                                           List<EvaluationDataset.GoldEvidence> gold) {
        double total = 0;
        for (KnowledgeTurnEvidence citation : citations) {
            double best = 0;
            for (EvaluationDataset.GoldEvidence expected : gold) {
                best = Math.max(best, intervalIou(citation.getStartMs(), citation.getEndMs(),
                        expected.startMs(), expected.endMs()));
            }
            total += best;
        }
        return total / citations.size();
    }

    private static boolean overlaps(KnowledgeTurnEvidence citation,
                                    EvaluationDataset.GoldEvidence expected) {
        return citation.getStartMs() != null && citation.getEndMs() != null
                && overlaps(citation.getStartMs(), citation.getEndMs(),
                expected.startMs(), expected.endMs());
    }

    private static boolean overlapsAny(long startMs, long endMs,
                                       List<EvaluationDataset.GoldEvidence> expected) {
        return expected.stream().anyMatch(gold -> overlaps(
                startMs, endMs, gold.startMs(), gold.endMs()));
    }

    private static boolean overlaps(long leftStart, long leftEnd,
                                    long rightStart, long rightEnd) {
        return Math.max(leftStart, rightStart) < Math.min(leftEnd, rightEnd);
    }

    private static double intervalIou(Long leftStart, Long leftEnd, long rightStart, long rightEnd) {
        if (leftStart == null || leftEnd == null || leftEnd <= leftStart) return 0;
        long intersection = Math.max(0,
                Math.min(leftEnd, rightEnd) - Math.max(leftStart, rightStart));
        long union = Math.max(leftEnd, rightEnd) - Math.min(leftStart, rightStart);
        return union <= 0 ? 0 : (double) intersection / union;
    }

    private static String normalized(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
