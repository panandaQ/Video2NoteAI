package com.example.server.evaluation.runner;

import com.example.server.dto.knowledge.AnswerMode;
import com.example.server.dto.knowledge.AnswerOutcome;
import com.example.server.dto.knowledge.AnswerRequest;
import com.example.server.service.knowledge.KnowledgeQuestionExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeEvaluationRunnerTest {

    private final KnowledgeQuestionExecutor executor = mock(KnowledgeQuestionExecutor.class);
    private final MediaReferenceResolver mediaResolver = mock(MediaReferenceResolver.class);
    private final RetrievalProbe retrievalProbe = mock(RetrievalProbe.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final KnowledgeEvaluationRunner runner = new KnowledgeEvaluationRunner(
            executor, mediaResolver, retrievalProbe, java.util.Optional.empty(), objectMapper);

    @Test
    void runsSelectedVariantsWithIndependentConversationHistory() throws Exception {
        byte[] dataset = datasetJson().getBytes(StandardCharsets.UTF_8);
        when(mediaResolver.resolve("sha256:abc", null))
                .thenReturn(new MediaReferenceResolver.ResolvedMedia(7L, 62L, "abc"));
        when(executor.answer(any())).thenAnswer(invocation -> {
            AnswerRequest request = invocation.getArgument(0);
            return new AnswerOutcome(request.question(), "HYBRID", 1, List.of(),
                    AnswerMode.VIDEO_GROUNDED, true, "回答-" + request.question(), 10, 0, 0);
        });
        when(retrievalProbe.top5(any())).thenReturn(new RetrievalProbe.ProbeResult(List.of()));

        EvaluationReport report = runner.run(dataset, new EvaluationRunRequest(
                "run-1", "commit-1", "answer-prompt-v1", "deepseek-chat",
                Map.of("temperature", 0), List.of(EvaluationVariant.B, EvaluationVariant.D), null));

        assertEquals("run-1", report.metadata().runId());
        assertEquals(2, report.variants().size());
        assertEquals(2, report.variants().get(0).summary().totalTurns());
        assertEquals(2, report.variants().get(1).summary().totalTurns());
        assertTrue(report.metadata().actualDatasetSha256().matches("[0-9a-f]{64}"));
        EvaluationReport.MetricAggregate hitAt5 = report.variants().getFirst()
                .summary().metrics().get("hitAt5");
        assertEquals(1, hitAt5.evaluatedCount());
        assertEquals(1, hitAt5.unavailableCount());
        assertEquals("ClaimJudge is not configured", report.variants().getFirst()
                .summary().metrics().get("unsupportedClaimRate").unavailableReason());

        ArgumentCaptor<AnswerRequest> requests = ArgumentCaptor.forClass(AnswerRequest.class);
        verify(executor, org.mockito.Mockito.times(4)).answer(requests.capture());
        assertEquals(0, requests.getAllValues().get(0).history().size());
        assertEquals(1, requests.getAllValues().get(1).history().size());
        assertEquals(0, requests.getAllValues().get(2).history().size());
        assertEquals(1, requests.getAllValues().get(3).history().size());
    }

    @Test
    void recordsTurnFailureAndContinuesFollowingCases() throws Exception {
        byte[] dataset = datasetJson().getBytes(StandardCharsets.UTF_8);
        when(mediaResolver.resolve("sha256:abc", null))
                .thenReturn(new MediaReferenceResolver.ResolvedMedia(7L, 62L, "abc"));
        when(executor.answer(any()))
                .thenThrow(new IllegalStateException("provider down"))
                .thenReturn(new AnswerOutcome("第二问", "HYBRID", 0, List.of(),
                        AnswerMode.MODEL_KNOWLEDGE, false, "回答", 5, 0, 0));
        when(retrievalProbe.top5(any())).thenReturn(new RetrievalProbe.ProbeResult(List.of()));

        EvaluationReport report = runner.run(dataset, new EvaluationRunRequest(
                "run-2", "commit-2", "p-v1", "model", Map.of(),
                List.of(EvaluationVariant.D), null));

        EvaluationReport.VariantSummary summary = report.variants().getFirst().summary();
        assertEquals(2, summary.totalTurns());
        assertEquals(1, summary.succeededTurns());
        assertEquals(1, summary.failedTurns());
        assertEquals("EXECUTION_FAILED", report.variants().getFirst().cases().getFirst()
                .turns().getFirst().errorCode());
    }

    @Test
    void reportsRetrievalProbeFailureAsUnavailableInsteadOfFabricatingScores() {
        byte[] dataset = datasetJson().getBytes(StandardCharsets.UTF_8);
        when(mediaResolver.resolve("sha256:abc", null))
                .thenReturn(new MediaReferenceResolver.ResolvedMedia(7L, 62L, "abc"));
        when(executor.answer(any())).thenAnswer(invocation -> {
            AnswerRequest request = invocation.getArgument(0);
            return new AnswerOutcome(request.question(), "HYBRID", 1, List.of(),
                    AnswerMode.VIDEO_GROUNDED, true, "回答", 2, 0, 0);
        });
        when(retrievalProbe.top5(any())).thenThrow(new IllegalStateException("qdrant down"));

        EvaluationReport report = runner.run(dataset, new EvaluationRunRequest(
                "run-3", "commit-3", "p-v1", "model", Map.of(),
                List.of(EvaluationVariant.D), null));

        EvaluationReport.MetricAggregate hitAt5 = report.variants().getFirst()
                .summary().metrics().get("hitAt5");
        assertEquals(0, hitAt5.evaluatedCount());
        assertEquals("RetrievalProbe failed: IllegalStateException", hitAt5.unavailableReason());
        assertEquals("RetrievalProbe failed: IllegalStateException", report.variants().getFirst()
                .summary().unavailableMetrics().get("hitAt5"));
    }

    private String datasetJson() {
        return """
                {
                  "datasetVersion": "v1",
                  "provenance": {
                    "productionAnswerModel": "deepseek-chat",
                    "retrievalParamsSnapshot": {"maxEvidence": 8},
                    "datasetSha256": "declared"
                  },
                  "cases": [{
                    "conversationCaseId": "case-1",
                    "mediaRef": "sha256:abc",
                    "sourceVideoTag": "video-a",
                    "turns": [
                      {"turnNo": 1, "category": "首轮直接问题", "question": "第一问", "standaloneQuestion": "第一问", "answerable": true, "answerKeyPoints": ["回答"], "goldEvidence": [{"startMs": 1, "endMs": 2, "sourceType": "CC"}]},
                      {"turnNo": 2, "category": "指代追问", "question": "第二个呢？", "standaloneQuestion": "第二问", "answerable": false, "answerKeyPoints": [], "goldEvidence": []}
                    ]
                  }]
                }
                """;
    }
}
