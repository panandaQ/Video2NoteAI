package com.example.server.evaluation.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Golden 单视频多轮会话数据集的稳定读取模型。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationDataset(
        String datasetVersion,
        Provenance provenance,
        List<ConversationCase> cases
) {
    public EvaluationDataset {
        datasetVersion = textOrUnknown(datasetVersion);
        provenance = provenance == null
                ? new Provenance(null, null, null, null, null, Map.of(), null)
                : provenance;
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("evaluation dataset cases must not be empty");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provenance(
            String generatorModel,
            String generatorPromptVersion,
            String judgeModel,
            String judgePromptVersion,
            String productionAnswerModel,
            Map<String, Object> retrievalParamsSnapshot,
            String datasetSha256
    ) {
        public Provenance {
            retrievalParamsSnapshot = retrievalParamsSnapshot == null
                    ? Map.of() : Map.copyOf(retrievalParamsSnapshot);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConversationCase(
            String conversationCaseId,
            String mediaRef,
            String sourceVideoTag,
            String sourceDistributionNote,
            List<GoldenTurn> turns
    ) {
        public ConversationCase {
            requireText(conversationCaseId, "conversationCaseId");
            requireText(mediaRef, "mediaRef");
            turns = turns == null ? List.of() : List.copyOf(turns);
            if (turns.isEmpty()) {
                throw new IllegalArgumentException("case " + conversationCaseId + " has no turns");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoldenTurn(
            int turnNo,
            String category,
            String question,
            String standaloneQuestion,
            boolean answerable,
            List<String> answerKeyPoints,
            List<GoldEvidence> goldEvidence
    ) {
        public GoldenTurn {
            if (turnNo <= 0) throw new IllegalArgumentException("turnNo must be positive");
            requireText(question, "question");
            standaloneQuestion = standaloneQuestion == null || standaloneQuestion.isBlank()
                    ? question : standaloneQuestion;
            category = textOrUnknown(category);
            answerKeyPoints = answerKeyPoints == null ? List.of() : List.copyOf(answerKeyPoints);
            goldEvidence = goldEvidence == null ? List.of() : List.copyOf(goldEvidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoldEvidence(long startMs, long endMs, String sourceType) {
        public GoldEvidence {
            if (startMs < 0 || endMs <= startMs) {
                throw new IllegalArgumentException("gold evidence must have 0 <= startMs < endMs");
            }
            sourceType = textOrUnknown(sourceType);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static String textOrUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
