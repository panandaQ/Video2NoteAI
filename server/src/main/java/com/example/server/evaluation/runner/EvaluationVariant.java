package com.example.server.evaluation.runner;

import com.example.server.dto.knowledge.AnswerOptions;

/** A/B/C/D 消融档位；唯一差异是传给生产问答执行器的 {@link AnswerOptions}。 */
public enum EvaluationVariant {
    A(AnswerOptions.A),
    B(AnswerOptions.B),
    C(AnswerOptions.C),
    D(AnswerOptions.D);

    private final AnswerOptions options;

    EvaluationVariant(AnswerOptions options) {
        this.options = options;
    }

    public AnswerOptions options() {
        return options;
    }
}
