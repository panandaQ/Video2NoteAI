package com.example.server.evaluation.dataset;

import java.time.Duration;

/** 独立 Judge 连接配置；API key 只从环境传入且不得序列化到报告。 */
public record JudgeModelConfig(String apiKey, String baseUrl, String model, Duration timeout) {

    public JudgeModelConfig {
        apiKey = required(apiKey, "JUDGE_API_KEY_REQUIRED");
        baseUrl = required(baseUrl, "JUDGE_BASE_URL_REQUIRED");
        model = required(model, "JUDGE_MODEL_REQUIRED");
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("JUDGE_TIMEOUT_INVALID");
        }
    }

    public static void validateIsolation(String generatorModel,
                                         String declaredJudgeModel,
                                         String productionAnswerModel,
                                         String runtimeJudgeModel) {
        String generator = required(generatorModel, "GENERATOR_MODEL_REQUIRED");
        String declaredJudge = required(declaredJudgeModel, "DECLARED_JUDGE_MODEL_REQUIRED");
        String production = required(productionAnswerModel, "PRODUCTION_ANSWER_MODEL_REQUIRED");
        String runtime = required(runtimeJudgeModel, "RUNTIME_JUDGE_MODEL_REQUIRED");
        if (!declaredJudge.equalsIgnoreCase(runtime)) {
            throw new IllegalArgumentException("JUDGE_MODEL_PROVENANCE_MISMATCH");
        }
        if (runtime.equalsIgnoreCase(generator)) {
            throw new IllegalArgumentException("JUDGE_EQUALS_GENERATOR_MODEL");
        }
        if (runtime.equalsIgnoreCase(production)) {
            throw new IllegalArgumentException("JUDGE_EQUALS_PRODUCTION_MODEL");
        }
        if (generator.equalsIgnoreCase(production)) {
            throw new IllegalArgumentException("GENERATOR_EQUALS_PRODUCTION_MODEL");
        }
    }

    private static String required(String value, String code) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
        return value.trim();
    }

    @Override
    public String toString() {
        return "JudgeModelConfig[apiKey=<redacted>, baseUrl=" + baseUrl
                + ", model=" + model + ", timeout=" + timeout + "]";
    }
}
