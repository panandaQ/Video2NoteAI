package com.example.server.evaluation.dataset;

import com.example.server.evaluation.runner.EvaluationDataset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeValidationServiceTest {

    @Test
    void validatesEntailmentAndLeakageWithEvidenceTranscriptOnly() {
        EvaluationDataset dataset = dataset("generator-a", "judge-b", "production-c");
        ChunkCorpus corpus = new ChunkCorpus(List.of(new ChunkCorpus.Video(
                "sha256:abc", List.of(new ChunkCorpus.Segment(10_000, 20_000, "直接支持要点")))));
        CapturingJudgeClient client = new CapturingJudgeClient(
                "{\"entailmentPass\":true,\"failedKeyPoints\":[],\"leaksAnswer\":false}");
        JudgeValidationService service = new JudgeValidationService(client);

        JudgeValidationService.Report report = service.validate(dataset, corpus);

        assertTrue(report.passed());
        assertEquals("直接支持要点", client.lastRequest.evidenceText());
        assertEquals("独立问题", client.lastRequest.standaloneQuestion());
        assertEquals(List.of("要点"), client.lastRequest.answerKeyPoints());
    }

    @Test
    void rejectsLeakingQuestionEvenWhenEntailmentPasses() {
        EvaluationDataset dataset = dataset("generator-a", "judge-b", "production-c");
        ChunkCorpus corpus = new ChunkCorpus(List.of(new ChunkCorpus.Video(
                "sha256:abc", List.of(new ChunkCorpus.Segment(10_000, 20_000, "直接支持要点")))));
        JudgeValidationService service = new JudgeValidationService(request ->
                "{\"entailmentPass\":true,\"failedKeyPoints\":[],\"leaksAnswer\":true}");

        JudgeValidationService.Report report = service.validate(dataset, corpus);

        assertFalse(report.passed());
        assertEquals("ANSWER_LEAK", report.turns().getFirst().diagnosticCode());
    }

    @Test
    void modelIsolationFailsClosedWhenNamesAreMissingOrEqual() {
        assertThrows(IllegalArgumentException.class,
                () -> JudgeModelConfig.validateIsolation("judge", "judge", "production", "judge"));
        assertThrows(IllegalArgumentException.class,
                () -> JudgeModelConfig.validateIsolation("generator", "judge", " ", "judge"));
        assertThrows(IllegalArgumentException.class,
                () -> JudgeModelConfig.validateIsolation("generator", "judge", "production", "other"));
        assertThrows(IllegalArgumentException.class,
                () -> JudgeModelConfig.validateIsolation("production", "judge", "production", "judge"));

        JudgeModelConfig.validateIsolation("generator", "judge", "production", "judge");
    }

    private EvaluationDataset dataset(String generator, String judge, String production) {
        EvaluationDataset.GoldenTurn turn = new EvaluationDataset.GoldenTurn(
                1, "首轮直接问题", "问题", "独立问题", true, List.of("要点"),
                List.of(new EvaluationDataset.GoldEvidence(10_000, 20_000, "CC")));
        return new EvaluationDataset("v1", new EvaluationDataset.Provenance(
                generator, "gen-v1", judge, "judge-v1", production, null, null),
                List.of(new EvaluationDataset.ConversationCase(
                        "case-1", "sha256:abc", "video-A", "CC", List.of(turn))));
    }

    private static final class CapturingJudgeClient implements JudgeModelClient {
        private final String response;
        private JudgeRequest lastRequest;

        private CapturingJudgeClient(String response) {
            this.response = response;
        }

        @Override
        public String judge(JudgeRequest request) {
            lastRequest = request;
            return response;
        }
    }
}
