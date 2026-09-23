package com.example.server.evaluation.dataset;

import java.util.List;

/** 独立 Judge 的最小输入；不包含密钥、模型配置或环境 mediaId。 */
public record JudgeRequest(String evidenceText,
                           String standaloneQuestion,
                           List<String> answerKeyPoints) {
    public JudgeRequest {
        evidenceText = evidenceText == null ? "" : evidenceText;
        standaloneQuestion = standaloneQuestion == null ? "" : standaloneQuestion.trim();
        answerKeyPoints = answerKeyPoints == null ? List.of() : List.copyOf(answerKeyPoints);
    }
}
