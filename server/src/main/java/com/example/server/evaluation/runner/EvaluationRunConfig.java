package com.example.server.evaluation.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** CLI JSON 配置。路径与运行元数据显式记录，避免依赖机器隐式状态。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationRunConfig(
        String datasetPath,
        String outputPath,
        Long userId,
        List<EvaluationVariant> variants,
        String runId,
        String codeCommit,
        String promptVersion,
        String model,
        Map<String, Object> modelParameters
) {
    public EvaluationRunConfig {
        if (datasetPath == null || datasetPath.isBlank()) {
            throw new IllegalArgumentException("datasetPath is required");
        }
        if (outputPath == null || outputPath.isBlank()) {
            throw new IllegalArgumentException("outputPath is required");
        }
        variants = variants == null || variants.isEmpty()
                ? List.of(EvaluationVariant.values())
                : List.copyOf(new LinkedHashSet<>(variants));
        modelParameters = modelParameters == null ? Map.of() : Map.copyOf(modelParameters);
    }

    public EvaluationRunRequest toRunRequest(String detectedCommit, String datasetModel) {
        return new EvaluationRunRequest(runId,
                firstText(codeCommit, detectedCommit),
                promptVersion,
                firstText(model, datasetModel),
                modelParameters,
                variants,
                userId);
    }

    public EvaluationRunConfig withPaths(String datasetOverride, String outputOverride) {
        return new EvaluationRunConfig(
                firstText(datasetOverride, datasetPath),
                firstText(outputOverride, outputPath),
                userId, variants, runId, codeCommit, promptVersion, model, modelParameters);
    }

    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
