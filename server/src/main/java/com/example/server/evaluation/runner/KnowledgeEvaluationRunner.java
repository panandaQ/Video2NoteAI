package com.example.server.evaluation.runner;

import com.example.server.dto.knowledge.AnswerMode;
import com.example.server.dto.knowledge.AnswerOutcome;
import com.example.server.dto.knowledge.AnswerRequest;
import com.example.server.dto.knowledge.HistoryTurn;
import com.example.server.entity.KnowledgeTurnEvidence;
import com.example.server.service.knowledge.KnowledgeQuestionExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Q2 质量评测 Runner：只调用 {@link KnowledgeQuestionExecutor#answer(AnswerRequest)}，
 * 不经过 HTTP、SSE、会话事务，也不复制任何生产问答步骤。
 */
@Component
public class KnowledgeEvaluationRunner {

    private final KnowledgeQuestionExecutor executor;
    private final MediaReferenceResolver mediaResolver;
    private final RetrievalProbe retrievalProbe;
    private final Optional<ClaimJudge> claimJudge;
    private final ObjectMapper objectMapper;

    public KnowledgeEvaluationRunner(KnowledgeQuestionExecutor executor,
                                     MediaReferenceResolver mediaResolver,
                                     RetrievalProbe retrievalProbe,
                                     Optional<ClaimJudge> claimJudge,
                                     ObjectMapper objectMapper) {
        this.executor = executor;
        this.mediaResolver = mediaResolver;
        this.retrievalProbe = retrievalProbe;
        this.claimJudge = claimJudge;
        this.objectMapper = objectMapper;
    }

    public EvaluationReport run(byte[] datasetBytes, EvaluationRunRequest request) {
        Objects.requireNonNull(datasetBytes, "datasetBytes");
        Objects.requireNonNull(request, "request");
        Instant startedAt = Instant.now();
        EvaluationDataset dataset = readDataset(datasetBytes);
        claimJudge.ifPresent(judge -> judge.validateIsolation(dataset.provenance()));
        String actualHash = EvaluationDatasetHasher.sha256(datasetBytes, objectMapper);
        Map<String, Resolution> resolutions = resolveMedia(dataset, request.userId());

        List<EvaluationReport.VariantResult> variants = new ArrayList<>();
        for (EvaluationVariant variant : request.variants()) {
            variants.add(runVariant(dataset, resolutions, variant, request.retrievalTopK()));
        }

        String declaredHash = dataset.provenance().datasetSha256();
        Boolean hashMatches = isSha256(declaredHash)
                ? declaredHash.equalsIgnoreCase(actualHash) : null;
        EvaluationReport.Metadata metadata = new EvaluationReport.Metadata(
                request.runId(), startedAt, Instant.now(), request.codeCommit(),
                dataset.datasetVersion(), actualHash, declaredHash, hashMatches,
                dataset.provenance().generatorModel(),
                dataset.provenance().generatorPromptVersion(),
                dataset.provenance().judgeModel(),
                dataset.provenance().judgePromptVersion(),
                request.promptVersion(), request.model(), request.modelParameters(),
                retrievalParameters(dataset, request.retrievalTopK()), request.variants());
        return new EvaluationReport(metadata, metricDefinitions(request.retrievalTopK()), List.copyOf(variants));
    }

    /**
     * Streams one completed turn at a time and retains only the current case in
     * memory. The normal JSON report remains available through {@link #run};
     * this path is intended for long evaluations and crash-resilient JSONL logs.
     */
    public void runStreaming(byte[] datasetBytes, EvaluationRunRequest request,
                             EvaluationProgressSink sink) {
        Objects.requireNonNull(datasetBytes, "datasetBytes");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        Instant startedAt = Instant.now();
        EvaluationDataset dataset = readDataset(datasetBytes);
        claimJudge.ifPresent(judge -> judge.validateIsolation(dataset.provenance()));
        String actualHash = EvaluationDatasetHasher.sha256(datasetBytes, objectMapper);
        Map<String, Resolution> resolutions = resolveMedia(dataset, request.userId());
        String declaredHash = dataset.provenance().datasetSha256();
        Boolean hashMatches = isSha256(declaredHash)
                ? declaredHash.equalsIgnoreCase(actualHash) : null;
        EvaluationReport.Metadata startedMetadata = metadata(request, dataset, actualHash,
                declaredHash, hashMatches, startedAt, null);
        sink.onRunStarted(startedMetadata, metricDefinitions(request.retrievalTopK()));

        Map<EvaluationVariant, EvaluationReport.VariantSummary> summaries = new LinkedHashMap<>();
        for (EvaluationVariant variant : request.variants()) {
            SummaryAccumulator accumulator = new SummaryAccumulator();
            for (EvaluationDataset.ConversationCase goldenCase : dataset.cases()) {
                EvaluationReport.CaseResult result = runCaseStreaming(
                        goldenCase, resolutions.get(goldenCase.mediaRef()), variant,
                        request.retrievalTopK(),
                        resultTurn -> {
                            accumulator.accept(resultTurn);
                            sink.onTurn(variant, goldenCase.conversationCaseId(), resultTurn);
                        });
                sink.onCaseCompleted(variant, result);
            }
            EvaluationReport.VariantSummary summary = accumulator.toSummary();
            summaries.put(variant, summary);
            sink.onVariantCompleted(variant, summary);
        }
        EvaluationReport.Metadata completedMetadata = metadata(request, dataset, actualHash,
                declaredHash, hashMatches, startedAt, Instant.now());
        sink.onRunCompleted(completedMetadata, Map.copyOf(summaries));
    }

    private EvaluationReport.Metadata metadata(EvaluationRunRequest request,
                                               EvaluationDataset dataset,
                                               String actualHash,
                                               String declaredHash,
                                               Boolean hashMatches,
                                               Instant startedAt,
                                               Instant completedAt) {
        return new EvaluationReport.Metadata(
                request.runId(), startedAt, completedAt, request.codeCommit(),
                dataset.datasetVersion(), actualHash, declaredHash, hashMatches,
                dataset.provenance().generatorModel(),
                dataset.provenance().generatorPromptVersion(),
                dataset.provenance().judgeModel(),
                dataset.provenance().judgePromptVersion(),
                request.promptVersion(), request.model(), request.modelParameters(),
                retrievalParameters(dataset, request.retrievalTopK()), request.variants());
    }

    private Map<String, Object> retrievalParameters(EvaluationDataset dataset, int retrievalTopK) {
        Map<String, Object> parameters = new LinkedHashMap<>(
                dataset.provenance().retrievalParamsSnapshot());
        parameters.put("evaluationTopK", retrievalTopK);
        return Map.copyOf(parameters);
    }

    private EvaluationReport.VariantResult runVariant(EvaluationDataset dataset,
                                                       Map<String, Resolution> resolutions,
                                                       EvaluationVariant variant,
                                                       int retrievalTopK) {
        List<EvaluationReport.CaseResult> cases = new ArrayList<>();
        for (EvaluationDataset.ConversationCase goldenCase : dataset.cases()) {
            cases.add(runCase(goldenCase, resolutions.get(goldenCase.mediaRef()), variant, retrievalTopK));
        }
        List<EvaluationReport.TurnResult> allTurns = cases.stream()
                .flatMap(result -> result.turns().stream()).toList();
        return new EvaluationReport.VariantResult(
                variant, variant.options(), List.copyOf(cases), summarize(allTurns));
    }

    private EvaluationReport.CaseResult runCase(EvaluationDataset.ConversationCase goldenCase,
                                                 Resolution resolution,
                                                 EvaluationVariant variant,
                                                 int retrievalTopK) {
        return runCaseStreaming(goldenCase, resolution, variant, retrievalTopK, ignored -> { });
    }

    private EvaluationReport.CaseResult runCaseStreaming(
            EvaluationDataset.ConversationCase goldenCase,
            Resolution resolution,
            EvaluationVariant variant,
            int retrievalTopK,
            Consumer<EvaluationReport.TurnResult> turnConsumer) {
        if (resolution.error() != null) {
            List<EvaluationReport.TurnResult> failed = new ArrayList<>();
            for (EvaluationDataset.GoldenTurn turn : goldenCase.turns()) {
                EvaluationReport.TurnResult result = failedTurn(turn, "MEDIA_REF_UNRESOLVED", resolution.error());
                failed.add(result);
                turnConsumer.accept(result);
            }
            return new EvaluationReport.CaseResult(
                    goldenCase.conversationCaseId(), goldenCase.mediaRef(),
                    goldenCase.sourceVideoTag(), null, null, List.copyOf(failed), summarize(failed));
        }

        MediaReferenceResolver.ResolvedMedia media = resolution.media();
        List<HistoryTurn> history = new ArrayList<>();
        List<EvaluationReport.TurnResult> turns = new ArrayList<>();
        for (EvaluationDataset.GoldenTurn gold : goldenCase.turns()) {
            EvaluationReport.TurnResult result;
            try {
                AnswerRequest answerRequest = new AnswerRequest(
                        media.userId(), media.mediaId(), gold.question(), history, variant.options());
                AnswerOutcome outcome = executor.answer(answerRequest);
                ProbeAttempt probe = probe(media.mediaId(), outcome, retrievalTopK);
                JudgeAttempt judge = judge(gold, outcome, probe.result());
                EvaluationMetrics metrics = EvaluationMetrics.calculate(
                        gold, outcome, variant, probe.result(), probe.error(),
                        judge.judgement(), judge.error());
                List<EvaluationReport.EvidenceResult> evidence = mapEvidence(outcome.evidence());
                result = new EvaluationReport.TurnResult(
                        gold.turnNo(), gold.category(), gold.question(), gold.standaloneQuestion(),
                        gold.answerable(), outcome.answerMode().name(), outcome.answer(),
                        outcome.rewrittenQuery(), outcome.retrievalMode(), outcome.retrievedCount(),
                        mapHits(probe.result()), evidence, metrics, systemMetrics(outcome.durationMs()),
                        "COMPLETED", null, null,
                        outcome.rawCitationCount(), outcome.fabricatedCitationCount(), outcome.queryPlan());
                history.add(new HistoryTurn(gold.turnNo(), gold.question(), outcome.answer()));
            } catch (RuntimeException e) {
                result = failedTurn(gold, "EXECUTION_FAILED", controlledMessage(e));
            }
            turns.add(result);
            turnConsumer.accept(result);
        }
        List<EvaluationReport.TurnResult> immutableTurns = List.copyOf(turns);
        return new EvaluationReport.CaseResult(
                goldenCase.conversationCaseId(), goldenCase.mediaRef(),
                goldenCase.sourceVideoTag(), media.userId(), media.mediaId(),
                immutableTurns, summarize(immutableTurns));
    }

    private EvaluationReport.TurnResult failedTurn(EvaluationDataset.GoldenTurn gold,
                                                    String errorCode,
                                                    String errorMessage) {
        return new EvaluationReport.TurnResult(
                gold.turnNo(), gold.category(), gold.question(), gold.standaloneQuestion(),
                gold.answerable(), null, null, null, null, null, List.of(), List.of(),
                EvaluationMetrics.unavailable(errorCode),
                new EvaluationReport.SystemMetrics(null, null, null, null,
                        Map.of("modelUsage", "Execution did not complete")),
                "FAILED", errorCode, errorMessage, 0, 0, null);
    }

    private EvaluationReport.SystemMetrics systemMetrics(long durationMs) {
        return new EvaluationReport.SystemMetrics(durationMs, null, null, null,
                Map.of("modelCalls", "KnowledgeQuestionExecutor.answer does not expose call counts",
                        "estimatedTokens", "KnowledgeQuestionExecutor.answer does not expose token usage",
                        "estimatedCost", "KnowledgeQuestionExecutor.answer does not expose cost"));
    }

    private List<EvaluationReport.EvidenceResult> mapEvidence(List<KnowledgeTurnEvidence> evidence) {
        List<EvaluationReport.EvidenceResult> result = new ArrayList<>(evidence.size());
        for (int i = 0; i < evidence.size(); i++) {
            KnowledgeTurnEvidence row = evidence.get(i);
            result.add(new EvaluationReport.EvidenceResult(
                    row.getEvidenceRank() == null ? i + 1 : row.getEvidenceRank(),
                    row.getStartMs() == null ? 0 : row.getStartMs(),
                    row.getEndMs() == null ? 0 : row.getEndMs(),
                    row.getSource(), row.getSnippet(), row.getScore(), "", null));
        }
        return List.copyOf(result);
    }

    private List<EvaluationReport.EvidenceResult> mapHits(RetrievalProbe.ProbeResult probe) {
        if (probe == null) return List.of();
        List<EvaluationReport.EvidenceResult> result = new ArrayList<>();
        for (int i = 0; i < probe.rankedTop5().size(); i++) {
            var hit = probe.rankedTop5().get(i);
            result.add(new EvaluationReport.EvidenceResult(
                    i + 1, hit.startMs(), hit.endMs(), hit.source(), hit.snippet(),
                    hit.score(), hit.chunkRef(), hit.scoreBreakdown()));
        }
        return List.copyOf(result);
    }

    private EvaluationReport.VariantSummary summarize(List<EvaluationReport.TurnResult> turns) {
        int succeeded = (int) turns.stream().filter(turn -> "COMPLETED".equals(turn.status())).count();
        int failed = turns.size() - succeeded;

        // 来源模式准确率：gold.answerable=true 期望视频可答（VIDEO_GROUNDED/HYBRID），
        // false 期望模型知识兜底（MODEL_KNOWLEDGE）。
        List<EvaluationReport.TurnResult> modeEvaluated = turns.stream()
                .filter(turn -> turn.actualAnswerMode() != null).toList();
        Double answerModeAccuracy = modeEvaluated.isEmpty() ? null
                : modeEvaluated.stream()
                .filter(turn -> modeMatches(turn.expectedAnswerable(), turn.actualAnswerMode()))
                .count() / (double) modeEvaluated.size();

        // 伪造引用率：模型声称引用中无效 ID 的占比（按轮聚合）。
        int rawCitations = turns.stream().mapToInt(EvaluationReport.TurnResult::rawCitationCount).sum();
        int fabricatedCitations = turns.stream()
                .mapToInt(EvaluationReport.TurnResult::fabricatedCitationCount).sum();
        Double fabricatedCitationRate = rawCitations == 0 ? null
                : (double) fabricatedCitations / rawCitations;

        // 虚假视频归因率：视频 grounded 模式下，ClaimJudge 判为不被视频证据支持的声明占比。
        List<Double> attributionValues = turns.stream()
                .filter(turn -> "VIDEO_GROUNDED".equals(turn.actualAnswerMode())
                        || "HYBRID".equals(turn.actualAnswerMode()))
                .map(EvaluationReport.TurnResult::metrics)
                .map(EvaluationMetrics::unsupportedClaimRate)
                .filter(Objects::nonNull)
                .toList();
        Double falseVideoAttributionRate = attributionValues.isEmpty() ? null
                : attributionValues.stream().mapToDouble(Double::doubleValue).average().orElseThrow();

        Map<String, EvaluationReport.MetricAggregate> metrics = new LinkedHashMap<>();
        Map<String, String> unavailableMetrics = new LinkedHashMap<>();
        for (String name : EvaluationMetrics.metricNames()) {
            List<Double> values = turns.stream()
                    .map(EvaluationReport.TurnResult::metrics)
                    .map(metric -> metric.value(name))
                    .filter(Objects::nonNull)
                    .toList();
            String reason = values.size() == turns.size() ? null
                    : values.isEmpty() ? firstUnavailableReason(turns, name)
                    : "Partially unavailable; inspect per-turn unavailableReasons";
            metrics.put(name, new EvaluationReport.MetricAggregate(
                    average(values), values.size(), turns.size() - values.size(), reason));
            if (reason != null) unavailableMetrics.put(name, reason);
        }

        List<Long> durations = turns.stream()
                .map(EvaluationReport.TurnResult::systemMetrics)
                .map(EvaluationReport.SystemMetrics::durationMs)
                .filter(Objects::nonNull)
                .sorted().toList();
        return new EvaluationReport.VariantSummary(
                turns.size(), succeeded, failed, answerModeAccuracy, modeEvaluated.size(),
                falseVideoAttributionRate, fabricatedCitationRate,
                Map.copyOf(metrics), Map.copyOf(unavailableMetrics),
                percentile(durations, 0.50), percentile(durations, 0.95),
                null, null, null);
    }

    /** gold.answerable=true 期望视频可答（VIDEO_GROUNDED/HYBRID），false 期望 MODEL_KNOWLEDGE。 */
    private static boolean modeMatches(boolean expectedAnswerable, String actualMode) {
        if (expectedAnswerable) {
            return "VIDEO_GROUNDED".equals(actualMode) || "HYBRID".equals(actualMode);
        }
        return "MODEL_KNOWLEDGE".equals(actualMode);
    }

    private ProbeAttempt probe(Long mediaId, AnswerOutcome outcome, int retrievalTopK) {
        if (outcome.retrievalHits() != null) {
            return new ProbeAttempt(RetrievalProbe.ProbeResult.topN(
                    outcome.retrievalHits(), retrievalTopK), null);
        }
        try {
            RetrievalProbe.ProbeRequest request = new RetrievalProbe.ProbeRequest(
                    mediaId, outcome.rewrittenQuery());
            RetrievalProbe.ProbeResult result = retrievalProbe.topK(request, retrievalTopK);
            // Mockito/test adapters and legacy implementations may not implement the default method.
            if (result == null) result = retrievalProbe.top5(request);
            return new ProbeAttempt(result, null);
        } catch (RuntimeException e) {
            return new ProbeAttempt(null,
                    "RetrievalProbe failed: " + e.getClass().getSimpleName());
        }
    }

    private JudgeAttempt judge(EvaluationDataset.GoldenTurn gold,
                               AnswerOutcome outcome,
                               RetrievalProbe.ProbeResult retrievalTop5) {
        if (outcome.answerMode() == AnswerMode.MODEL_KNOWLEDGE) {
            return new JudgeAttempt(null, "Actual output is MODEL_KNOWLEDGE and carries no video-attributed claims");
        }
        if (claimJudge.isEmpty()) {
            return new JudgeAttempt(null, "ClaimJudge is not configured");
        }
        try {
            return new JudgeAttempt(claimJudge.orElseThrow().judge(
                    new ClaimJudge.JudgeRequest(
                            gold.question(), gold.standaloneQuestion(), outcome, retrievalTop5)), null);
        } catch (RuntimeException e) {
            return new JudgeAttempt(null,
                    "ClaimJudge failed: " + e.getClass().getSimpleName());
        }
    }

    private String firstUnavailableReason(List<EvaluationReport.TurnResult> turns, String metric) {
        return turns.stream().map(EvaluationReport.TurnResult::metrics)
                .map(EvaluationMetrics::unavailableReasons)
                .map(reasons -> reasons.get(metric))
                .filter(Objects::nonNull)
                .findFirst().orElse("Metric is unavailable");
    }

    private Double average(List<Double> values) {
        return values.isEmpty() ? null
                : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private Long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return null;
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private Map<String, Resolution> resolveMedia(EvaluationDataset dataset, Long userId) {
        Map<String, Resolution> result = new LinkedHashMap<>();
        for (EvaluationDataset.ConversationCase goldenCase : dataset.cases()) {
            result.computeIfAbsent(goldenCase.mediaRef(), ref -> {
                try {
                    return new Resolution(mediaResolver.resolve(ref, userId), null);
                } catch (RuntimeException e) {
                    return new Resolution(null, controlledMessage(e));
                }
            });
        }
        return result;
    }

    private EvaluationDataset readDataset(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, EvaluationDataset.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid evaluation dataset JSON", e);
        }
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    private String controlledMessage(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static Map<String, String> metricDefinitions() {
        return metricDefinitions(5);
    }

    private static Map<String, String> metricDefinitions(int retrievalTopK) {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put("rewriteAccuracy", "Normalized exact match against standaloneQuestion; follow-up turns in C/D only");
        definitions.put("hitAt5", "1 when any real RetrievalProbe Top-" + retrievalTopK + " interval overlaps gold evidence, otherwise 0");
        definitions.put("recallAt5", "Fraction of gold evidence intervals overlapped by real RetrievalProbe Top-" + retrievalTopK + " hits");
        definitions.put("mrr", "Reciprocal rank of the first real RetrievalProbe Top-" + retrievalTopK + " hit overlapping gold evidence");
        definitions.put("answerKeyPointRecall", "Fraction of normalized gold key points found verbatim in the answer");
        definitions.put("citationPrecision", "Fraction of verified citations whose time interval overlaps any gold interval; D only");
        definitions.put("timestampOverlapRate", "Mean best interval IoU between each verified citation and gold evidence; D only");
        definitions.put("unsupportedClaimRate", "Fraction of answer claims not directly supported by retrieved video evidence; unavailable when the independent ClaimJudge is not configured");
        definitions.put("answerModeAccuracy", "Supplemental: actual answer mode matches the expected mode derived from gold.answerable (true -> VIDEO_GROUNDED/HYBRID, false -> MODEL_KNOWLEDGE); failed executions excluded");
        definitions.put("falseVideoAttributionRate", "Supplemental: mean unsupported-claim rate among VIDEO_GROUNDED/HYBRID turns, i.e. model knowledge wrongly attributed to video; unavailable when the independent ClaimJudge is not configured");
        definitions.put("fabricatedCitationRate", "Supplemental: fraction of model-cited evidence IDs that were unknown/invalid (fabricated) across all turns");
        return Map.copyOf(definitions);
    }

    /** Incremental equivalent of summarize(List), retaining no completed results. */
    private static final class SummaryAccumulator {
        private final Map<String, MetricAccumulator> metrics = new LinkedHashMap<>();
        private final List<Long> durations = new ArrayList<>();
        private int totalTurns;
        private int succeededTurns;
        private int failedTurns;
        private int answerModeEvaluatedCount;
        private int answerModeMatches;
        private int rawCitations;
        private int fabricatedCitations;
        private double unsupportedClaimSum;
        private int unsupportedClaimCount;

        private SummaryAccumulator() {
            for (String name : EvaluationMetrics.metricNames()) metrics.put(name, new MetricAccumulator());
        }

        private void accept(EvaluationReport.TurnResult turn) {
            totalTurns++;
            if ("COMPLETED".equals(turn.status())) succeededTurns++;
            else failedTurns++;
            if (turn.actualAnswerMode() != null) {
                answerModeEvaluatedCount++;
                if (modeMatches(turn.expectedAnswerable(), turn.actualAnswerMode())) answerModeMatches++;
            }
            rawCitations += turn.rawCitationCount();
            fabricatedCitations += turn.fabricatedCitationCount();
            if (turn.systemMetrics() != null && turn.systemMetrics().durationMs() != null) {
                durations.add(turn.systemMetrics().durationMs());
            }
            EvaluationMetrics turnMetrics = turn.metrics();
            for (String name : EvaluationMetrics.metricNames()) {
                Double value = turnMetrics == null ? null : turnMetrics.value(name);
                String reason = turnMetrics == null ? "Metrics are unavailable" :
                        turnMetrics.unavailableReasons().get(name);
                metrics.get(name).accept(value, reason);
            }
            if (("VIDEO_GROUNDED".equals(turn.actualAnswerMode())
                    || "HYBRID".equals(turn.actualAnswerMode()))
                    && turnMetrics != null && turnMetrics.unsupportedClaimRate() != null) {
                unsupportedClaimSum += turnMetrics.unsupportedClaimRate();
                unsupportedClaimCount++;
            }
        }

        private EvaluationReport.VariantSummary toSummary() {
            Map<String, EvaluationReport.MetricAggregate> aggregates = new LinkedHashMap<>();
            Map<String, String> unavailable = new LinkedHashMap<>();
            for (Map.Entry<String, MetricAccumulator> entry : metrics.entrySet()) {
                EvaluationReport.MetricAggregate aggregate = entry.getValue().toAggregate(totalTurns);
                aggregates.put(entry.getKey(), aggregate);
                if (aggregate.unavailableReason() != null) {
                    unavailable.put(entry.getKey(), aggregate.unavailableReason());
                }
            }
            List<Long> sortedDurations = durations.stream().sorted().toList();
            return new EvaluationReport.VariantSummary(
                    totalTurns, succeededTurns, failedTurns,
                    answerModeEvaluatedCount == 0 ? null
                            : answerModeMatches / (double) answerModeEvaluatedCount,
                    answerModeEvaluatedCount,
                    unsupportedClaimCount == 0 ? null : unsupportedClaimSum / unsupportedClaimCount,
                    rawCitations == 0 ? null : fabricatedCitations / (double) rawCitations,
                    Map.copyOf(aggregates), Map.copyOf(unavailable),
                    percentile(sortedDurations, 0.50), percentile(sortedDurations, 0.95),
                    null, null, null);
        }

        private Long percentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) return null;
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(Math.min(index, sorted.size() - 1));
        }
    }

    private static final class MetricAccumulator {
        private double sum;
        private int evaluatedCount;
        private int unavailableCount;
        private String firstUnavailableReason;

        private void accept(Double value, String reason) {
            if (value != null) {
                sum += value;
                evaluatedCount++;
            } else {
                unavailableCount++;
                if (firstUnavailableReason == null && reason != null && !reason.isBlank()) {
                    firstUnavailableReason = reason;
                }
            }
        }

        private EvaluationReport.MetricAggregate toAggregate(int totalTurns) {
            String reason = unavailableCount == 0 ? null
                    : evaluatedCount == 0 ? firstUnavailableReason
                    : "Partially unavailable; inspect per-turn unavailableReasons";
            return new EvaluationReport.MetricAggregate(
                    evaluatedCount == 0 ? null : sum / evaluatedCount,
                    evaluatedCount, Math.max(unavailableCount, totalTurns - evaluatedCount), reason);
        }
    }

    private record Resolution(MediaReferenceResolver.ResolvedMedia media, String error) { }

    private record ProbeAttempt(RetrievalProbe.ProbeResult result, String error) { }

    private record JudgeAttempt(ClaimJudge.Judgement judgement, String error) { }
}
