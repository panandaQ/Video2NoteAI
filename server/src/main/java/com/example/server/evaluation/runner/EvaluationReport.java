package com.example.server.evaluation.runner;

import com.example.server.dto.knowledge.AnswerOptions;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 逐案例、逐轮与汇总结果的稳定 JSON 输出模型。 */
public record EvaluationReport(
        Metadata metadata,
        Map<String, String> metricDefinitions,
        List<VariantResult> variants
) {
    public record Metadata(
            String runId,
            Instant startedAt,
            Instant completedAt,
            String codeCommit,
            String datasetVersion,
            String actualDatasetSha256,
            String declaredDatasetSha256,
            Boolean datasetSha256Matches,
            String generatorModel,
            String generatorPromptVersion,
            String datasetJudgeModel,
            String datasetJudgePromptVersion,
            String promptVersion,
            String model,
            Map<String, Object> modelParameters,
            Map<String, Object> retrievalParameters,
            List<EvaluationVariant> variants
    ) { }

    public record VariantResult(
            EvaluationVariant variant,
            AnswerOptions options,
            List<CaseResult> cases,
            VariantSummary summary
    ) { }

    public record CaseResult(
            String conversationCaseId,
            String mediaRef,
            String sourceVideoTag,
            Long resolvedUserId,
            Long resolvedMediaId,
            List<TurnResult> turns,
            VariantSummary summary
    ) { }

    public record TurnResult(
            int turnNo,
            String category,
            String question,
            String standaloneQuestion,
            boolean expectedAnswerable,
            String actualAnswerMode,
            String answer,
            String rewrittenQuery,
            String retrievalMode,
            Integer retrievedCount,
            List<EvidenceResult> retrievalTop5,
            List<EvidenceResult> evidence,
            EvaluationMetrics metrics,
            SystemMetrics systemMetrics,
            String status,
            String errorCode,
            String errorMessage,
            int rawCitationCount,
            int fabricatedCitationCount
    ) { }

    public record EvidenceResult(
            int rank,
            long startMs,
            long endMs,
            String source,
            String snippet
    ) { }

    public record SystemMetrics(
            Long durationMs,
            Long modelCalls,
            Long estimatedTokens,
            Double estimatedCost,
            Map<String, String> unavailableReasons
    ) { }

    public record VariantSummary(
            int totalTurns,
            int succeededTurns,
            int failedTurns,
            Double answerModeAccuracy,
            int answerModeEvaluatedCount,
            Double falseVideoAttributionRate,
            Double fabricatedCitationRate,
            Map<String, MetricAggregate> metrics,
            Map<String, String> unavailableMetrics,
            Long durationP50Ms,
            Long durationP95Ms,
            Long modelCalls,
            Long estimatedTokens,
            Double estimatedCost
    ) { }

    public record MetricAggregate(
            Double value,
            int evaluatedCount,
            int unavailableCount,
            String unavailableReason
    ) { }
}
