package com.example.server.evaluation.runner;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一次可重复评测运行的配置快照；不会改变生产问答配置。 */
public record EvaluationRunRequest(
        String runId,
        String codeCommit,
        String promptVersion,
        String model,
        Map<String, Object> modelParameters,
        List<EvaluationVariant> variants,
        Long userId
) {
    public EvaluationRunRequest {
        runId = textOr(runId, UUID.randomUUID().toString());
        codeCommit = textOr(codeCommit, "UNKNOWN");
        promptVersion = textOr(promptVersion, "UNKNOWN");
        model = textOr(model, "UNKNOWN");
        modelParameters = modelParameters == null ? Map.of() : Map.copyOf(modelParameters);
        variants = variants == null || variants.isEmpty()
                ? List.of(EvaluationVariant.values())
                : List.copyOf(new LinkedHashSet<>(variants));
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
