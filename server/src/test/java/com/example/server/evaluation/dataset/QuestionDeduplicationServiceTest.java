package com.example.server.evaluation.dataset;

import com.example.server.evaluation.runner.EvaluationDataset;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestionDeduplicationServiceTest {

    private final EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
    private final QuestionDeduplicationService service = new QuestionDeduplicationService(embeddingUtils);

    @Test
    void dropsLaterQuestionOnlyWithinSameVideoWhenSimilarityExceedsThreshold() {
        EvaluationDataset dataset = dataset(
                conversation("case-a", "video-A", "问题一"),
                conversation("case-b", "video-A", "问题二"),
                conversation("case-c", "video-B", "问题三"));
        when(embeddingUtils.embed("问题一")).thenReturn(List.of(1.0, 0.0));
        when(embeddingUtils.embed("问题二")).thenReturn(List.of(0.99, 0.01));
        when(embeddingUtils.embed("问题三")).thenReturn(List.of(0.99, 0.01));

        QuestionDeduplicationService.Report report = service.deduplicate(dataset, 0.92);

        assertEquals(3, report.totalCandidates());
        assertEquals(2, report.keptCandidates());
        assertEquals(1, report.droppedCandidates());
        assertFalse(report.decisions().get(1).kept());
        assertEquals("case-a#1", report.decisions().get(1).duplicateOf());
        assertTrue(report.decisions().get(2).kept());
    }

    @Test
    void similarityEqualToThresholdIsKept() {
        EvaluationDataset dataset = dataset(
                conversation("case-a", "video-A", "问题一"),
                conversation("case-b", "video-A", "问题二"));
        when(embeddingUtils.embed("问题一")).thenReturn(List.of(1.0, 0.0));
        when(embeddingUtils.embed("问题二")).thenReturn(List.of(0.92, Math.sqrt(1 - 0.92 * 0.92)));

        QuestionDeduplicationService.Report report = service.deduplicate(dataset, 0.92);

        assertEquals(2, report.keptCandidates());
    }

    private EvaluationDataset dataset(EvaluationDataset.ConversationCase... cases) {
        return new EvaluationDataset("v1",
                new EvaluationDataset.Provenance(
                        "generator", "gen-v1", "judge", "judge-v1", "production", null, null),
                List.of(cases));
    }

    private EvaluationDataset.ConversationCase conversation(String id, String videoTag, String question) {
        return new EvaluationDataset.ConversationCase(
                id, "sha256:" + id, videoTag, "CC",
                List.of(new EvaluationDataset.GoldenTurn(
                        1, "首轮直接问题", question, question, true, List.of(), List.of())));
    }
}
