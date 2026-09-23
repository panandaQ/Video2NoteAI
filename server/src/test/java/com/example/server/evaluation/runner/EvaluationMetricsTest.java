package com.example.server.evaluation.runner;

import com.example.server.dto.knowledge.AnswerMode;
import com.example.server.dto.knowledge.AnswerOutcome;
import com.example.server.entity.KnowledgeTurnEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvaluationMetricsTest {

    @Test
    void calculatesOnlyMetricsSupportedByAnswerOutcome() {
        EvaluationDataset.GoldenTurn turn = new EvaluationDataset.GoldenTurn(
                2, "指代追问", "第二种呢？", "第二种方案有什么代价？", true,
                List.of("并发窗口", "脏读风险"),
                List.of(new EvaluationDataset.GoldEvidence(100L, 200L, "CC")));
        AnswerOutcome outcome = new AnswerOutcome(
                "第二种方案有什么代价？", "HYBRID", 5,
                List.of(evidence(120, 180), evidence(300, 400)),
                AnswerMode.VIDEO_GROUNDED, true, "该方案存在并发窗口。", 42, 2, 0);

        RetrievalProbe.ProbeResult probe = new RetrievalProbe.ProbeResult(List.of(
                hit(90, 210), hit(300, 400), hit(500, 600)));
        EvaluationMetrics metrics = EvaluationMetrics.calculate(
                turn, outcome, EvaluationVariant.D, probe, null, null, null);

        assertEquals(1.0, metrics.rewriteAccuracy());
        assertEquals(1.0, metrics.hitAt5());
        assertEquals(1.0, metrics.recallAt5());
        assertEquals(1.0, metrics.mrr());
        assertEquals(0.5, metrics.answerKeyPointRecall());
        assertEquals(0.5, metrics.citationPrecision());
        assertEquals(0.3, metrics.timestampOverlapRate(), 0.0001);
        assertNull(metrics.unsupportedClaimRate());
    }

    @Test
    void marksAblatedAndUndefinedMetricsAsNull() {
        EvaluationDataset.GoldenTurn turn = new EvaluationDataset.GoldenTurn(
                1, "首轮直接问题", "问题", "问题", false,
                List.of(), List.of());
        AnswerOutcome outcome = new AnswerOutcome(
                "问题", "HYBRID", 0, List.of(), AnswerMode.MODEL_KNOWLEDGE, false, "回答", 1, 0, 0);

        EvaluationMetrics metrics = EvaluationMetrics.calculate(
                turn, outcome, EvaluationVariant.A,
                new RetrievalProbe.ProbeResult(List.of()), null,
                null, "ClaimJudge is not configured");

        assertNull(metrics.rewriteAccuracy());
        assertNull(metrics.answerKeyPointRecall());
        assertNull(metrics.citationPrecision());
        assertNull(metrics.timestampOverlapRate());
        assertNull(metrics.unsupportedClaimRate());
        assertEquals("Actual output is MODEL_KNOWLEDGE and carries no video-attributed claims",
                metrics.unavailableReasons().get("unsupportedClaimRate"));
    }

    @Test
    void usesIndependentClaimJudgeResultWhenProvided() {
        EvaluationDataset.GoldenTurn turn = new EvaluationDataset.GoldenTurn(
                2, "指代追问", "它呢？", "第二种方案呢？", true,
                List.of("要点"), List.of(new EvaluationDataset.GoldEvidence(10, 20, "CC")));
        AnswerOutcome outcome = new AnswerOutcome(
                "第二种方案呢？", "HYBRID", 1, List.of(evidence(10, 20)),
                AnswerMode.VIDEO_GROUNDED, true, "要点", 1, 1, 0);

        EvaluationMetrics metrics = EvaluationMetrics.calculate(
                turn, outcome, EvaluationVariant.D,
                new RetrievalProbe.ProbeResult(List.of(hit(10, 20))), null,
                new ClaimJudge.Judgement(0.25, 4, 1), null);

        assertEquals(0.25, metrics.unsupportedClaimRate());
    }

    private KnowledgeTurnEvidence evidence(long startMs, long endMs) {
        KnowledgeTurnEvidence evidence = new KnowledgeTurnEvidence();
        evidence.setStartMs(startMs);
        evidence.setEndMs(endMs);
        evidence.setSource("CC");
        evidence.setSnippet("片段");
        return evidence;
    }

    private com.example.server.dto.VideoEvidenceHit hit(long startMs, long endMs) {
        return new com.example.server.dto.VideoEvidenceHit(
                startMs, endMs, "CC", "片段", "片段", List.of());
    }
}
