package com.example.server.evaluation.dataset;

import com.example.server.evaluation.runner.EvaluationDataset;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 同视频候选问题的确定性贪心去重：输入顺序靠前者保留，相似度严格大于阈值才丢弃。 */
@Service
public class QuestionDeduplicationService {

    private final EmbeddingUtils embeddingUtils;

    public QuestionDeduplicationService(EmbeddingUtils embeddingUtils) {
        this.embeddingUtils = embeddingUtils;
    }

    public Report deduplicate(EvaluationDataset dataset, double threshold) {
        if (dataset == null) throw new IllegalArgumentException("DATASET_REQUIRED");
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1) {
            throw new IllegalArgumentException("DEDUP_THRESHOLD_INVALID");
        }
        List<Candidate> candidates = flatten(dataset);
        if (candidates.isEmpty()) throw new IllegalArgumentException("DEDUP_CANDIDATES_REQUIRED");
        List<CandidateVector> kept = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        Integer expectedDimension = null;
        for (Candidate candidate : candidates) {
            List<Double> vector = embeddingUtils.embed(candidate.question());
            if (vector == null || vector.isEmpty()) throw new IllegalStateException("DEDUP_EMBEDDING_EMPTY");
            if (expectedDimension == null) expectedDimension = vector.size();
            if (vector.size() != expectedDimension) {
                throw new IllegalStateException("DEDUP_EMBEDDING_DIMENSION_MISMATCH");
            }

            CandidateVector duplicate = null;
            double highest = -1;
            for (CandidateVector existing : kept) {
                if (!existing.candidate().sourceVideoTag().equals(candidate.sourceVideoTag())) continue;
                double similarity = cosine(existing.vector(), vector);
                if (similarity > threshold && similarity > highest) {
                    duplicate = existing;
                    highest = similarity;
                }
            }
            if (duplicate == null) {
                kept.add(new CandidateVector(candidate, List.copyOf(vector)));
                decisions.add(new Decision(candidate.id(), candidate.sourceVideoTag(), true,
                        null, null, "KEPT"));
            } else {
                decisions.add(new Decision(candidate.id(), candidate.sourceVideoTag(), false,
                        duplicate.candidate().id(), highest, "SIMILARITY_ABOVE_THRESHOLD"));
            }
        }
        int keptCount = (int) decisions.stream().filter(Decision::kept).count();
        return new Report("question-dedup-v1", threshold, candidates.size(), keptCount,
                candidates.size() - keptCount, decisions);
    }

    private List<Candidate> flatten(EvaluationDataset dataset) {
        List<Candidate> candidates = new ArrayList<>();
        for (EvaluationDataset.ConversationCase conversationCase : dataset.cases()) {
            for (EvaluationDataset.GoldenTurn turn : conversationCase.turns()) {
                candidates.add(new Candidate(
                        conversationCase.conversationCaseId() + "#" + turn.turnNo(),
                        conversationCase.sourceVideoTag(), turn.standaloneQuestion()));
            }
        }
        return candidates;
    }

    private double cosine(List<Double> left, List<Double> right) {
        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            if (!Double.isFinite(leftValue) || !Double.isFinite(rightValue)) {
                throw new IllegalStateException("DEDUP_EMBEDDING_NON_FINITE");
            }
            dot += leftValue * rightValue;
            leftLength += leftValue * leftValue;
            rightLength += rightValue * rightValue;
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }

    private record Candidate(String id, String sourceVideoTag, String question) {
        private Candidate {
            if (sourceVideoTag == null || sourceVideoTag.isBlank()) {
                throw new IllegalArgumentException("SOURCE_VIDEO_TAG_REQUIRED");
            }
            sourceVideoTag = sourceVideoTag.trim();
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("DEDUP_QUESTION_REQUIRED");
            }
        }
    }

    private record CandidateVector(Candidate candidate, List<Double> vector) {
    }

    public record Report(String schemaVersion,
                         double threshold,
                         int totalCandidates,
                         int keptCandidates,
                         int droppedCandidates,
                         List<Decision> decisions) {
        public Report {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    public record Decision(String candidateId,
                           String sourceVideoTag,
                           boolean kept,
                           String duplicateOf,
                           Double similarity,
                           String diagnosticCode) {
    }
}
