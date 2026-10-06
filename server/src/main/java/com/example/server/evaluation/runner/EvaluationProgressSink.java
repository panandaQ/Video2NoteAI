package com.example.server.evaluation.runner;

import java.util.Map;

/** Receives evaluation results as soon as each unit completes. */
public interface EvaluationProgressSink {

    void onRunStarted(EvaluationReport.Metadata metadata, Map<String, String> metricDefinitions);

    void onTurn(EvaluationVariant variant, String conversationCaseId,
                EvaluationReport.TurnResult result);

    void onCaseCompleted(EvaluationVariant variant, EvaluationReport.CaseResult result);

    void onVariantCompleted(EvaluationVariant variant, EvaluationReport.VariantSummary summary);

    void onRunCompleted(EvaluationReport.Metadata metadata,
                        Map<EvaluationVariant, EvaluationReport.VariantSummary> summaries);
}
