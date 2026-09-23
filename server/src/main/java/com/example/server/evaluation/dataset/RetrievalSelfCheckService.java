package com.example.server.evaluation.dataset;

import com.example.server.dto.VideoEvidenceHit;
import com.example.server.evaluation.runner.EvaluationDataset;
import com.example.server.service.VideoEvidenceRetrievalService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 用生产检索链验证 standaloneQuestion 的金标准时间范围是否进入 Top-K。 */
@Service
public class RetrievalSelfCheckService {

    private final DatasetMediaResolver mediaResolver;
    private final VideoEvidenceRetrievalService retrievalService;

    public RetrievalSelfCheckService(DatasetMediaResolver mediaResolver,
                                     VideoEvidenceRetrievalService retrievalService) {
        this.mediaResolver = mediaResolver;
        this.retrievalService = retrievalService;
    }

    public Report check(EvaluationDataset dataset, int topK) {
        if (dataset == null) throw new IllegalArgumentException("DATASET_REQUIRED");
        if (topK <= 0) throw new IllegalArgumentException("TOP_K_MUST_BE_POSITIVE");
        List<TurnResult> results = new ArrayList<>();
        for (EvaluationDataset.ConversationCase conversationCase : dataset.cases()) {
            DatasetMediaResolver.ResolvedMedia media = mediaResolver.resolve(conversationCase.mediaRef());
            for (EvaluationDataset.GoldenTurn turn : conversationCase.turns()) {
                List<VideoEvidenceHit> hits = retrievalService.search(
                                media.mediaId(), turn.standaloneQuestion(), media.chunks()).stream()
                        .limit(topK)
                        .toList();
                results.add(result(conversationCase, turn, hits, topK));
            }
        }
        return new Report("retrieval-self-check-v1", topK,
                results.stream().allMatch(TurnResult::passed), results);
    }

    private TurnResult result(EvaluationDataset.ConversationCase conversationCase,
                              EvaluationDataset.GoldenTurn turn,
                              List<VideoEvidenceHit> hits,
                              int topK) {
        int matched = (int) turn.goldEvidence().stream()
                .filter(gold -> hits.stream().anyMatch(hit -> overlaps(gold, hit)))
                .count();
        boolean passed;
        String code;
        if (turn.answerable()) {
            passed = !turn.goldEvidence().isEmpty() && matched == turn.goldEvidence().size();
            code = passed ? "PASS" : "GOLD_EVIDENCE_NOT_IN_TOP_K";
        } else {
            passed = hits.isEmpty();
            code = passed ? "PASS" : "UNANSWERABLE_RETRIEVAL_NOT_EMPTY";
        }
        List<HitRange> ranges = java.util.stream.IntStream.range(0, hits.size())
                .mapToObj(index -> new HitRange(index + 1, hits.get(index).startMs(),
                        hits.get(index).endMs(), hits.get(index).source()))
                .toList();
        return new TurnResult(conversationCase.conversationCaseId(), turn.turnNo(),
                conversationCase.mediaRef(), conversationCase.sourceVideoTag(), turn.answerable(), topK,
                turn.goldEvidence().size(), matched, hits.size(), passed, code, ranges);
    }

    private boolean overlaps(EvaluationDataset.GoldEvidence gold, VideoEvidenceHit hit) {
        return Math.max(gold.startMs(), hit.startMs()) < Math.min(gold.endMs(), hit.endMs());
    }

    public record Report(String schemaVersion, int topK, boolean passed, List<TurnResult> turns) {
        public Report {
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
    }

    public record TurnResult(String conversationCaseId,
                             int turnNo,
                             String mediaRef,
                             String sourceVideoTag,
                             boolean answerable,
                             int topK,
                             int goldEvidenceCount,
                             int matchedGoldEvidence,
                             int returnedHits,
                             boolean passed,
                             String diagnosticCode,
                             List<HitRange> hits) {
        public TurnResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    public record HitRange(int rank, long startMs, long endMs, String sourceType) {
    }
}
