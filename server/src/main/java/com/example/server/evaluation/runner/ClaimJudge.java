package com.example.server.evaluation.runner;

import com.example.server.dto.knowledge.AnswerOutcome;

/**
 * 独立回答声明蕴含判分端口。实现必须使用与生产回答模型不同的 Judge；未配置时指标保持 unavailable。
 */
public interface ClaimJudge {

    Judgement judge(JudgeRequest request);

    /** Judge 启用时必须先证明与生成模型、生产回答模型隔离。 */
    default void validateIsolation(EvaluationDataset.Provenance provenance) {
        // 测试替身可以保持无状态；真实实现必须覆盖并 fail closed。
    }

    record JudgeRequest(
            String question,
            String standaloneQuestion,
            AnswerOutcome outcome,
            RetrievalProbe.ProbeResult retrievalTop5
    ) { }

    record Judgement(double unsupportedClaimRate, int totalClaims, int unsupportedClaims) {
        public Judgement {
            if (unsupportedClaimRate < 0 || unsupportedClaimRate > 1) {
                throw new IllegalArgumentException("unsupportedClaimRate must be in [0,1]");
            }
            if (totalClaims < 0 || unsupportedClaims < 0 || unsupportedClaims > totalClaims) {
                throw new IllegalArgumentException("invalid claim counts");
            }
        }
    }
}
