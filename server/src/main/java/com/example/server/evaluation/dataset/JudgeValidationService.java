package com.example.server.evaluation.dataset;

import com.example.server.evaluation.runner.EvaluationDataset;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** 对每个候选执行独立模型的蕴含/泄题门禁，报告不包含完整证据原文。 */
public final class JudgeValidationService {

    private final JudgeModelClient judgeClient;
    private final ObjectMapper objectMapper;

    public JudgeValidationService(JudgeModelClient judgeClient) {
        this(judgeClient, new ObjectMapper());
    }

    public JudgeValidationService(JudgeModelClient judgeClient, ObjectMapper objectMapper) {
        this.judgeClient = judgeClient;
        this.objectMapper = objectMapper;
    }

    public Report validate(EvaluationDataset dataset, ChunkCorpus corpus) {
        if (dataset == null) throw new IllegalArgumentException("DATASET_REQUIRED");
        if (corpus == null) throw new IllegalArgumentException("CHUNK_CORPUS_REQUIRED");
        List<TurnResult> results = new ArrayList<>();
        for (EvaluationDataset.ConversationCase conversationCase : dataset.cases()) {
            for (EvaluationDataset.GoldenTurn turn : conversationCase.turns()) {
                String evidenceText = corpus.evidenceText(conversationCase.mediaRef(), turn.goldEvidence());
                if (turn.answerable() && evidenceText.isBlank()) {
                    results.add(new TurnResult(conversationCase.conversationCaseId(), turn.turnNo(),
                            conversationCase.mediaRef(), false, false, List.of(), false,
                            "GOLD_EVIDENCE_TEXT_MISSING"));
                    continue;
                }
                JudgeDecision decision = parse(judgeClient.judge(new JudgeRequest(
                        evidenceText, turn.standaloneQuestion(), turn.answerKeyPoints())));
                boolean passed = decision.entailmentPass() && !decision.leaksAnswer();
                String code = passed ? "PASS"
                        : decision.leaksAnswer() ? "ANSWER_LEAK" : "ENTAILMENT_FAILED";
                results.add(new TurnResult(conversationCase.conversationCaseId(), turn.turnNo(),
                        conversationCase.mediaRef(), decision.entailmentPass(), decision.leaksAnswer(),
                        decision.failedKeyPoints(), passed, code));
            }
        }
        return new Report("judge-validation-v1", results.stream().allMatch(TurnResult::passed), results);
    }

    private JudgeDecision parse(String response) {
        if (response == null || response.isBlank()) throw new IllegalStateException("JUDGE_EMPTY_RESPONSE");
        String normalized = response.replace("```json", "").replace("```", "").trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("JUDGE_INVALID_JSON_RESPONSE");
        try {
            JudgeDecision decision = objectMapper.readValue(normalized.substring(start, end + 1), JudgeDecision.class);
            if (decision.failedKeyPoints() == null) {
                return new JudgeDecision(decision.entailmentPass(), List.of(), decision.leaksAnswer());
            }
            return decision;
        } catch (Exception e) {
            throw new IllegalStateException("JUDGE_INVALID_JSON_RESPONSE", e);
        }
    }

    public record JudgeDecision(boolean entailmentPass,
                                List<String> failedKeyPoints,
                                boolean leaksAnswer) {
        public JudgeDecision {
            failedKeyPoints = failedKeyPoints == null ? List.of() : List.copyOf(failedKeyPoints);
        }
    }

    public record Report(String schemaVersion, boolean passed, List<TurnResult> turns) {
        public Report {
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
    }

    public record TurnResult(String conversationCaseId,
                             int turnNo,
                             String mediaRef,
                             boolean entailmentPass,
                             boolean leaksAnswer,
                             List<String> failedKeyPoints,
                             boolean passed,
                             String diagnosticCode) {
        public TurnResult {
            failedKeyPoints = failedKeyPoints == null ? List.of() : List.copyOf(failedKeyPoints);
        }
    }
}
