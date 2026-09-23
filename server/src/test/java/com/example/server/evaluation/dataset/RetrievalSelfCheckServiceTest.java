package com.example.server.evaluation.dataset;

import com.example.server.dto.VideoEvidenceHit;
import com.example.server.evaluation.runner.EvaluationDataset;
import com.example.server.service.VideoEvidenceRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalSelfCheckServiceTest {

    private final DatasetMediaResolver mediaResolver = mock(DatasetMediaResolver.class);
    private final VideoEvidenceRetrievalService retrievalService = mock(VideoEvidenceRetrievalService.class);
    private final RetrievalSelfCheckService service =
            new RetrievalSelfCheckService(mediaResolver, retrievalService);

    @Test
    void answerableTurnPassesWhenEveryGoldRangeOverlapsTopK() {
        EvaluationDataset dataset = dataset(true,
                List.of(new EvaluationDataset.GoldEvidence(10_000, 20_000, "CC")));
        when(mediaResolver.resolve("sha256:abc"))
                .thenReturn(new DatasetMediaResolver.ResolvedMedia(99L, List.of()));
        when(retrievalService.search(eq(99L), eq("独立问题"), anyList()))
                .thenReturn(List.of(hit(12_000, 18_000), hit(30_000, 40_000)));

        RetrievalSelfCheckService.Report report = service.check(dataset, 5);

        assertTrue(report.passed());
        assertEquals(1, report.turns().getFirst().matchedGoldEvidence());
        assertEquals(2, report.turns().getFirst().returnedHits());
    }

    @Test
    void answerableTurnFailsWhenGoldFallsOutsideTopK() {
        EvaluationDataset dataset = dataset(true,
                List.of(new EvaluationDataset.GoldEvidence(10_000, 20_000, "CC")));
        when(mediaResolver.resolve("sha256:abc"))
                .thenReturn(new DatasetMediaResolver.ResolvedMedia(99L, List.of()));
        when(retrievalService.search(eq(99L), eq("独立问题"), anyList()))
                .thenReturn(List.of(hit(1, 2), hit(12_000, 18_000)));

        RetrievalSelfCheckService.Report report = service.check(dataset, 1);

        assertFalse(report.passed());
        assertEquals("GOLD_EVIDENCE_NOT_IN_TOP_K", report.turns().getFirst().diagnosticCode());
    }

    @Test
    void unanswerableTurnRequiresStrictRetrievalMiss() {
        EvaluationDataset dataset = dataset(false, List.of());
        when(mediaResolver.resolve("sha256:abc"))
                .thenReturn(new DatasetMediaResolver.ResolvedMedia(99L, List.of()));
        when(retrievalService.search(eq(99L), eq("独立问题"), anyList()))
                .thenReturn(List.of(hit(1, 2)));

        RetrievalSelfCheckService.Report report = service.check(dataset, 5);

        assertFalse(report.passed());
        assertEquals("UNANSWERABLE_RETRIEVAL_NOT_EMPTY", report.turns().getFirst().diagnosticCode());
    }

    private EvaluationDataset dataset(boolean answerable, List<EvaluationDataset.GoldEvidence> gold) {
        EvaluationDataset.GoldenTurn turn = new EvaluationDataset.GoldenTurn(
                1, "首轮直接问题", "问题", "独立问题", answerable, List.of("要点"), gold);
        EvaluationDataset.ConversationCase conversationCase = new EvaluationDataset.ConversationCase(
                "case-1", "sha256:abc", "video-A", "CC", List.of(turn));
        return new EvaluationDataset("v1",
                new EvaluationDataset.Provenance(
                        "generator", "gen-v1", "judge", "judge-v1", "production", null, null),
                List.of(conversationCase));
    }

    private VideoEvidenceHit hit(long startMs, long endMs) {
        return new VideoEvidenceHit(startMs, endMs, "CC", "", "", List.of());
    }
}
