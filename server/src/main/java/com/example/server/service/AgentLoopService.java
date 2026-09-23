package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoContext;
import com.example.server.service.mode.ModeProfile;
import com.example.server.utils.DeepSeekUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 受控 Agent 编排器：恢复状态、执行一轮分析、校验证据，再决定结束还是补跑。 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_PLAN_TASKS = 5;

    private final DeepSeekUtils deepSeekUtils;
    private final LongVideoContextService longVideoContextService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final EvidenceVerificationService evidenceVerificationService;
    private final TaskEventService taskEventService;
    private final AgentBudgetService budgetService;
    private final int maxRounds;
    private final double maxEstimatedCost;

    public AgentLoopService(DeepSeekUtils deepSeekUtils,
                            LongVideoContextService longVideoContextService,
                            AgentCheckpointService checkpointService,
                            AgentTelemetry telemetry,
                            EvidenceVerificationService evidenceVerificationService,
                            TaskEventService taskEventService,
                            AgentBudgetService budgetService) {
        this.deepSeekUtils = deepSeekUtils;
        this.longVideoContextService = longVideoContextService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.evidenceVerificationService = evidenceVerificationService;
        this.taskEventService = taskEventService;
        this.budgetService = budgetService;
        AgentBudgetEstimator.Settings settings = budgetService.settings();
        this.maxRounds = settings.maxRounds();
        this.maxEstimatedCost = budgetService.maxEstimatedCost();
    }

    public AgentState run(VideoContext context) {
        return run(null, context, null);
    }

    /** 兼容旧调用方:无模式 = GENERAL(空指令 Profile)。 */
    public AgentState run(Long mediaId, VideoContext context) {
        return run(mediaId, context, null);
    }

    /**
     * 执行一轮受控 Agent 分析。{@code profile} 为空时等价于 GENERAL——三段模式指令均为空串,
     * 拼接后 prompt 与引入模式体系前完全一致,因此默认行为不变。
     */
    public AgentState run(Long mediaId, VideoContext context, ModeProfile profile) {
        validateContext(context);
        // 动态预算（D-075）：在任何模型调用前按完整 Context 与章节规模一次性推导；
        // 现有 max-* 键是默认下限，hard-* 才是全局硬上限（AgentBudgetService/AgentBudgetEstimator）。
        AgentBudgetEstimator.Estimate budget = budgetService.estimate(context);
        telemetry.valueCurrent("effectiveTokenBudget", budget.effectiveTokenBudget());
        telemetry.valueCurrent("effectiveDurationMs", budget.effectiveDurationMs());
        telemetry.valueCurrent("estimatedCalls", budget.expectedCalls());
        telemetry.valueCurrent("chapterBatches", budget.chapterBatches());
        telemetry.valueCurrent("estimatedInputTokens",
                budgetService.estimateInputTokensPerBatch(context));
        log.info("agent_budget_derived mediaId={} effectiveTokens={} effectiveDurationMs={} "
                        + "expectedCalls={} chapterBatches={} chapters={} durationMs={}",
                mediaId, budget.effectiveTokenBudget(), budget.effectiveDurationMs(),
                budget.expectedCalls(), budget.chapterBatches(),
                context.chapters() == null ? 0 : context.chapters().size(),
                AgentBudgetService.effectiveDurationMs(context));
        try (AgentExecutionBudget.Scope ignored =
                     AgentExecutionBudget.open(budget.effectiveDurationMs())) {
            return runWithinBudget(mediaId, context, profile, budget);
        } catch (BudgetExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            AgentExecutionBudget.DeadlineExceededException deadline = findDeadline(e);
            if (deadline == null) throw e;
            telemetry.incrementCurrent("budgetTerminations", 1);
            throw new BudgetExceededException(deadline.getMessage(), e);
        }
    }

    private AgentState runWithinBudget(Long mediaId, VideoContext context, ModeProfile profile,
                                       AgentBudgetEstimator.Estimate budget) {
        long runStartedNanos = System.nanoTime();
        AgentState savedState = mediaId == null ? null
                : checkpointService.loadCriticState(mediaId, context.userGoal(), modeOf(profile));
        boolean terminalCheckpoint = savedState != null && savedState.result() != null
                && savedState.critique() != null
                && (savedState.round() >= maxRounds || savedState.critique().passed());
        if (terminalCheckpoint
                && isPlanValid(savedState.plan())
                && isResultValid(savedState.result(), profile)
                && chaptersValid(context, savedState.result())) {
            checkpointService.saveResult(mediaId, savedState, modeOf(profile));
            telemetry.incrementCurrent("terminalCheckpointHits", 1);
            return savedState;
        }
        if (terminalCheckpoint) {
            telemetry.incrementCurrent("invalidTerminalCheckpointRepairs", 1);
            savedState = new AgentState(
                    savedState.goal(), savedState.plan(), savedState.result(), savedState.critique(), 0);
        }

        VideoContext relevantContext = longVideoContextService.selectRelevant(mediaId, context);
        AgentState.AgentPlan plan = resolvePlan(mediaId, relevantContext, savedState, profile);
        checkBudget(runStartedNanos, "Planner", budget);
        if (mediaId != null) {
            taskEventService.publishAnalysis(mediaId, relevantContext.userGoal(), modeOf(profile),
                    TaskStatus.of(TaskStatus.State.PROCESSING, "Planner 已完成任务拆解"),
                    TaskStage.PLAN_COMPLETED);
        }
        AgentState state = savedState == null
                ? new AgentState(relevantContext.userGoal(), plan, null, null, 0)
                : savedState;
        if (state.critique() != null && !state.critique().passed()) {
            relevantContext = contextForRetry(
                    mediaId, context, relevantContext, state.critique(), profile);
            plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
        }

        // Executor 草稿已经落盘时，MQ 重试直接从 Critic 接着走，避免重复生成整份产物。
        if (state.result() != null && state.critique() == null && state.round() > 0) {
            telemetry.incrementCurrent("criticCheckpointResumes", 1);
            checkBudget(runStartedNanos, "Executor Checkpoint", budget);
            state = critiqueRound(mediaId, relevantContext, plan, state.result(), state.round(), profile);
            if (!state.critique().passed() && state.round() < maxRounds) {
                relevantContext = contextForRetry(
                        mediaId, context, relevantContext, state.critique(), profile);
                plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
            }
        }

        for (int round = state.round() + 1; round <= maxRounds; round++) {
            checkBudget(runStartedNanos, "Agent Round " + round, budget);
            state = executeRound(
                    mediaId, relevantContext, plan, state.critique(), round, runStartedNanos, profile, budget);
            if (state.critique().passed()) break;
            if (round < maxRounds) {
                relevantContext = contextForRetry(
                        mediaId, context, relevantContext, state.critique(), profile);
                plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
            }
        }
        validateResult(state.result(), profile);
        if (mediaId != null) checkpointService.saveResult(mediaId, state, modeOf(profile));
        return state;
    }

    private AgentState.AgentPlan resolvePlan(Long mediaId,
                                             VideoContext context,
                                             AgentState savedState,
                                             ModeProfile profile) {
        AgentState.AgentPlan plan = mediaId == null
                ? null
                : checkpointService.loadPlan(mediaId, context.userGoal(), modeOf(profile));
        if (plan == null && savedState != null) plan = savedState.plan();
        boolean shouldPersist = false;
        if (plan == null) {
            plan = deepSeekUtils.plan(context, planInstruction(profile));
            shouldPersist = true;
        }
        if (!isPlanValid(plan)) {
            plan = deepSeekUtils.repairPlan(context, plan, planInstruction(profile));
            telemetry.incrementCurrent("planStructureRepairs", 1);
            shouldPersist = true;
        }
        validatePlan(plan);
        if (mediaId != null && shouldPersist) {
            checkpointService.savePlan(mediaId, context.userGoal(), modeOf(profile), plan);
        }
        return plan;
    }

    private AgentState executeRound(Long mediaId,
                                    VideoContext context,
                                    AgentState.AgentPlan plan,
                                    AgentState.CriticResult previousCritique,
                                    int round,
                                    long runStartedNanos,
                                    ModeProfile profile,
                                    AgentBudgetEstimator.Estimate budget) {
        publishStage(mediaId, context.userGoal(), modeOf(profile),
                "Executor 正在按计划生成结构化产物", TaskStage.EXECUTOR_STARTED);
        AnalysisResult result = normalizeEvidenceSources(
                context, deepSeekUtils.execute(context, plan, previousCritique, executeInstruction(profile)));
        AgentState draft = new AgentState(context.userGoal(), plan, result, null, round);
        if (mediaId != null) {
            checkpointService.saveExecutionState(mediaId, draft, modeOf(profile));
            publishStage(mediaId, context.userGoal(), modeOf(profile),
                    "Executor 草稿已保存，开始校验证据", TaskStage.EXECUTOR_COMPLETED);
        }
        checkBudget(runStartedNanos, "Executor", budget);
        return critiqueRound(mediaId, context, plan, result, round, profile);
    }

    /**
     * 证据来源标签由代码按片段实际通道回填（D-099），不再信任模型自填。
     *
     * <p>media 66 实测：20 条证据的 {@code source} 全被模型写成 {@code CC}，其中 17 条正文其实逐字
     * 来自 {@code ocrTexts}（幻灯片文本）；而问答链路的同名字段是代码按规则算的。这里在 Critic
     * 之前校正，既让核验基于真实标签，也让落库产物可审计。
     */
    private AnalysisResult normalizeEvidenceSources(VideoContext context, AnalysisResult result) {
        if (result == null || result.evidence().isEmpty()) {
            return result;
        }
        List<AnalysisResult.Evidence> normalized = result.evidence().stream()
                .map(evidence -> {
                    String resolved = evidenceVerificationService.resolveSource(context, evidence);
                    return resolved == null ? evidence : new AnalysisResult.Evidence(
                            evidence.timestampMs(), resolved, evidence.content(), evidence.claim());
                })
                .toList();
        return new AnalysisResult(result.title(), result.conclusions(), normalized,
                result.suggestions(), result.sections());
    }

    private AgentState critiqueRound(Long mediaId,
                                     VideoContext context,
                                     AgentState.AgentPlan plan,
                                     AnalysisResult result,
                                     int round,
                                     ModeProfile profile) {
        publishStage(mediaId, context.userGoal(), modeOf(profile),
                "Critic 正在核验目标覆盖与时间戳证据", TaskStage.CRITIC_STARTED);
        AgentState.CriticResult critique = normalizeCritique(
                deepSeekUtils.critique(context, plan, result, criticInstruction(profile)));
        critique = enforceStructureBounds(result, critique, profile);
        critique = enforceEvidenceBounds(context, result, critique);
        critique = enforceChapterSections(context, result, critique);
        telemetry.incrementCurrent("criticRounds", 1);
        if (critique.passed()) telemetry.incrementCurrent("criticPassed", 1);

        AgentState state = new AgentState(context.userGoal(), plan, result, critique, round);
        if (mediaId != null) {
            checkpointService.saveCriticState(mediaId, state, modeOf(profile));
            String message;
            TaskStage stage;
            if (critique.passed()) {
                message = "Critic 校验通过，正在整理结构化结果";
                stage = TaskStage.CRITIC_PASSED;
            } else if (round >= maxRounds) {
                message = "Critic 达到最大校验轮次，正在保留警告并生成结果";
                stage = TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS;
            } else if (requiresEvidenceRefresh(critique)) {
                message = "Critic 发现证据缺口，正在定向补充证据";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            } else {
                message = "Critic 发现目标覆盖或结构问题，正在按反馈重写";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            }
            publishStage(mediaId, context.userGoal(), modeOf(profile), message, stage);
        }
        return state;
    }

    private void validateContext(VideoContext context) {
        if (context == null || context.userGoal() == null || context.userGoal().isBlank()
                || context.segments() == null || context.segments().isEmpty()
                || context.segments().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Agent 需要目标和至少一个视频片段");
        }
    }

    private void validatePlan(AgentState.AgentPlan plan) {
        if (!isPlanValid(plan)) {
            throw new IllegalStateException("Planner 返回了无效任务列表");
        }
    }

    private boolean isPlanValid(AgentState.AgentPlan plan) {
        return plan != null && plan.understoodGoal() != null && !plan.understoodGoal().isBlank()
                && plan.tasks() != null && !plan.tasks().isEmpty()
                && plan.tasks().size() <= MAX_PLAN_TASKS
                && plan.tasks().stream().noneMatch(
                task -> task == null || task.isBlank() || task.length() > 500);
    }

    /** 终态检查点复用前的章节完整性复核：缺章/乱序/证据越界的结果不能直接复用（交付约束 4）。 */
    private boolean chaptersValid(VideoContext context, AnalysisResult result) {
        return enforceChapterSections(context, result,
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()))
                .passed();
    }

    private void validateResult(AnalysisResult result, ModeProfile profile) {
        if (!isResultValid(result, profile)) {
            throw new IllegalStateException("Executor 未生成完整结构化结果");
        }
    }

    private boolean isResultValid(AnalysisResult result, ModeProfile profile) {
        boolean commonFieldsValid = result != null
                && result.title() != null && !result.title().isBlank()
                && result.conclusions() != null && !result.conclusions().isEmpty()
                && result.evidence() != null && !result.evidence().isEmpty();
        if (!commonFieldsValid || profile == null || profile.requiredSectionKeys().isEmpty()) {
            return commonFieldsValid;
        }
        List<String> presentKeys = result.sections().stream()
                .filter(section -> section != null && section.key() != null
                        && !section.key().isBlank() && !section.items().isEmpty())
                .map(section -> section.key().trim())
                .distinct()
                .toList();
        return presentKeys.containsAll(profile.requiredSectionKeys());
    }

    /**
     * Critic 强校验（计划 §5.3 交付约束 4）：有 View 章节时，缺章、乱序、多余段落、章节证据
     * 越界或把无证据章节写成事实均不得通过；无证据章节必须明确写"未提取到可核验证据"。
     */
    static AgentState.CriticResult enforceChapterSections(VideoContext context,
                                                          AnalysisResult result,
                                                          AgentState.CriticResult critique) {
        critique = normalizeCritique(critique);
        List<com.example.server.dto.VideoChapter> chapters = context == null || context.chapters() == null
                ? List.of() : context.chapters();
        if (chapters.isEmpty() || result == null) return critique;

        List<String> feedback = new ArrayList<>(critique.feedback());
        List<String> keys = result.sections().stream()
                .map(AnalysisResult.Section::key)
                .filter(key -> key != null && !key.isBlank())
                .map(String::trim)
                .toList();
        for (int i = 0; i < chapters.size(); i++) {
            String expected = "chapter:" + chapters.get(i).id();
            if (i >= keys.size() || !expected.equals(keys.get(i))) {
                feedback.add("章节段落缺失或顺序错误：第 " + (i + 1) + " 个段落必须是 " + expected
                        + "（" + chapters.get(i).title() + "）");
                break;
            }
        }
        if (keys.size() > chapters.size()) {
            feedback.add("出现了不属于平台章节的段落: " + String.join(", ", keys.subList(
                    chapters.size(), keys.size())));
        }
        for (int i = 0; i < chapters.size() && i < result.sections().size(); i++) {
            com.example.server.dto.VideoChapter chapter = chapters.get(i);
            AnalysisResult.Section section = result.sections().get(i);
            if (section.items().isEmpty()) {
                feedback.add(chapter.title() + " 段落缺少内容");
                continue;
            }
            boolean hasInRangeEvidence = result.evidence().stream()
                    .anyMatch(evidence -> evidence.timestampMs() >= chapter.startMs()
                            && evidence.timestampMs() < chapter.endMs());
            boolean declaresAbsence = section.items().stream()
                    .anyMatch(item -> item != null && item.contains("未提取到可核验证据"));
            if (!hasInRangeEvidence && !declaresAbsence) {
                feedback.add(chapter.title() + " 没有章内证据，也没有明确写“未提取到可核验证据”");
            }
        }
        if (feedback.equals(critique.feedback())) return critique;
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                critique.unsupportedClaims(),
                critique.requiredTimestamps());
    }

    private AgentState.CriticResult enforceEvidenceBounds(VideoContext context,
                                                           AnalysisResult result,
                                                           AgentState.CriticResult critique) {
        critique = normalizeCritique(critique);
        boolean hasDeclaredProblems = !critique.feedback().isEmpty()
                || !critique.missingRequirements().isEmpty()
                || !critique.unsupportedClaims().isEmpty()
                || !critique.requiredTimestamps().isEmpty();
        if (critique.passed() && hasDeclaredProblems) {
            critique = new AgentState.CriticResult(
                    false,
                    critique.feedback(),
                    critique.missingRequirements(),
                    critique.unsupportedClaims(),
                    critique.requiredTimestamps());
        }
        if (!critique.passed()
                && critique.feedback().isEmpty()
                && critique.missingRequirements().isEmpty()
                && critique.unsupportedClaims().isEmpty()
                && critique.requiredTimestamps().isEmpty()) {
            critique = new AgentState.CriticResult(
                    false,
                    List.of("重新检查目标覆盖、结构完整性和证据绑定"),
                    List.of(), List.of(), List.of());
        }
        if (result == null || result.evidence() == null || result.evidence().isEmpty()) return critique;
        List<AnalysisResult.Evidence> invalidEvidence = result.evidence().stream()
                .filter(evidence -> !evidenceVerificationService.supported(context, evidence))
                .toList();
        List<String> unsupportedClaims = result.conclusions().stream()
                .filter(claim -> result.evidence().stream().noneMatch(
                        evidence -> evidenceVerificationService.supportsClaim(context, claim, evidence)))
                .toList();
        if (invalidEvidence.isEmpty() && unsupportedClaims.isEmpty()) return critique;

        List<String> unsupported = new ArrayList<>(critique.unsupportedClaims());
        unsupportedClaims.stream()
                .filter(claim -> !unsupported.contains(claim))
                .forEach(unsupported::add);
        invalidEvidence.stream()
                .map(evidence -> "证据无法在原始 ASR/OCR 中核验: " + evidence.timestampMs())
                .forEach(unsupported::add);
        List<String> feedback = new ArrayList<>(critique.feedback());
        feedback.add("为每条结论重新检索并绑定可核验的时间戳证据");
        List<Long> requiredTimestamps = new ArrayList<>(critique.requiredTimestamps());
        invalidEvidence.stream()
                .map(AnalysisResult.Evidence::timestampMs)
                .filter(timestamp -> !requiredTimestamps.contains(timestamp))
                .forEach(requiredTimestamps::add);
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                unsupported,
                requiredTimestamps);
    }

    private AgentState.CriticResult enforceStructureBounds(AnalysisResult result,
                                                            AgentState.CriticResult critique,
                                                            ModeProfile profile) {
        critique = normalizeCritique(critique);
        List<String> feedback = new ArrayList<>(critique.feedback());
        if (result == null || result.title() == null || result.title().isBlank()) {
            feedback.add("补充明确的产物标题");
        }
        if (result == null || result.conclusions() == null || result.conclusions().isEmpty()) {
            feedback.add("补充覆盖 Planner 任务的核心结论");
        }
        if (result == null || result.evidence() == null || result.evidence().isEmpty()) {
            feedback.add("为核心结论补充带时间戳的 ASR 或 OCR 证据");
        }
        List<String> missingSections = missingSectionKeys(result, profile);
        if (!missingSections.isEmpty()) {
            feedback.add("补充当前分析模式要求的结构化段落: " + String.join(", ", missingSections));
        }
        if (feedback.equals(critique.feedback())) return critique;
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                critique.unsupportedClaims(),
                critique.requiredTimestamps());
    }

    private VideoContext contextForRetry(Long mediaId,
                                         VideoContext fullContext,
                                         VideoContext selectedContext,
                                         AgentState.CriticResult critique,
                                         ModeProfile profile) {
        if (!requiresEvidenceRefresh(critique)) {
            telemetry.incrementCurrent("criticRewriteOnlyRetries", 1);
            return selectedContext;
        }
        telemetry.incrementCurrent("criticEvidenceRefreshes", 1);
        VideoContext refined = longVideoContextService.refineForCritique(
                mediaId, fullContext, selectedContext, critique);
        publishStage(mediaId, fullContext.userGoal(), modeOf(profile),
                "已按 Critic 反馈补充定向证据", TaskStage.EVIDENCE_REFRESHED);
        return refined;
    }

    private boolean requiresEvidenceRefresh(AgentState.CriticResult critique) {
        return critique != null
                && (!safeList(critique.requiredTimestamps()).isEmpty()
                || !safeList(critique.missingRequirements()).isEmpty()
                || !safeList(critique.unsupportedClaims()).isEmpty());
    }

    private AgentState.AgentPlan revisePlanForRetry(Long mediaId,
                                                    VideoContext context,
                                                    AgentState.AgentPlan currentPlan,
                                                    AgentState.CriticResult critique,
                                                    ModeProfile profile) {
        if (critique == null || safeList(critique.missingRequirements()).isEmpty()) return currentPlan;

        try {
            AgentState.AgentPlan revisedPlan = deepSeekUtils.replan(
                    context, currentPlan, critique, planInstruction(profile));
            validatePlan(revisedPlan);
            telemetry.incrementCurrent("planRevisions", 1);
            if (mediaId != null) {
                checkpointService.savePlan(mediaId, context.userGoal(), modeOf(profile), revisedPlan);
                taskEventService.publishAnalysis(mediaId, context.userGoal(), modeOf(profile),
                        TaskStatus.of(TaskStatus.State.PROCESSING, "Planner 根据 Critic 反馈补充了遗漏任务"),
                        TaskStage.PLAN_COMPLETED);
            }
            return revisedPlan;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("planRevisionFallbacks", 1);
            log.warn("agent_replan_failed mediaId={}, fallback to current plan", mediaId, e);
            return currentPlan;
        }
    }

    private void publishStage(Long mediaId,
                              String goal,
                              AnalysisMode mode,
                              String message,
                              TaskStage stage) {
        if (mediaId == null) return;
        taskEventService.publishAnalysis(mediaId, goal, mode,
                TaskStatus.of(TaskStatus.State.PROCESSING, message), stage);
    }

    private List<String> missingSectionKeys(AnalysisResult result, ModeProfile profile) {
        if (profile == null || profile.requiredSectionKeys().isEmpty()) return List.of();
        List<String> presentKeys = result == null || result.sections() == null
                ? List.of()
                : result.sections().stream()
                        .filter(section -> section != null
                                && section.key() != null
                                && !section.key().isBlank()
                                && section.items() != null
                                && !section.items().isEmpty())
                        .map(section -> section.key().trim())
                        .distinct()
                        .toList();
        return profile.requiredSectionKeys().stream()
                .filter(required -> !presentKeys.contains(required))
                .toList();
    }

    private void checkBudget(long startedNanos, String completedStage,
                             AgentBudgetEstimator.Estimate budget) {
        AgentExecutionBudget.check(completedStage);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        AgentTelemetry.BudgetUsage usage = telemetry.currentUsage();
        String reason = null;
        if (elapsedMs > budget.effectiveDurationMs()) {
            reason = "Agent 超过最大执行时长 " + budget.effectiveDurationMs() + "ms";
        } else if (usage.estimatedTokens() > budget.effectiveTokenBudget()) {
            reason = "Agent 超过最大 Token 预算 " + budget.effectiveTokenBudget();
        } else if (maxEstimatedCost > 0 && usage.estimatedCost() > maxEstimatedCost) {
            reason = "Agent 超过最大成本预算 " + maxEstimatedCost;
        }
        if (reason == null) return;
        telemetry.incrementCurrent("budgetTerminations", 1);
        throw new BudgetExceededException(completedStage + " 后终止：" + reason);
    }

    public static class BudgetExceededException extends IllegalStateException {
        public BudgetExceededException(String message) {
            super(message);
        }

        public BudgetExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private AgentExecutionBudget.DeadlineExceededException findDeadline(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof AgentExecutionBudget.DeadlineExceededException deadline) {
                return deadline;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private static AgentState.CriticResult normalizeCritique(AgentState.CriticResult critique) {
        if (critique == null) {
            return new AgentState.CriticResult(
                    false, List.of("Critic 未返回有效结果"),
                    List.of(), List.of(), List.of());
        }
        return new AgentState.CriticResult(
                critique.passed(),
                safeList(critique.feedback()),
                safeList(critique.missingRequirements()),
                safeList(critique.unsupportedClaims()),
                safeList(critique.requiredTimestamps()));
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    // profile 为空(GENERAL)时返回空指令/GENERAL 模式,使 prompt 与 checkpoint 键都与引入模式前一致。
    private static AnalysisMode modeOf(ModeProfile profile) {
        return profile == null ? AnalysisMode.GENERAL : profile.mode();
    }

    private static String planInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.planInstruction();
    }

    private static String executeInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.executeInstruction();
    }

    private static String criticInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.criticInstruction();
    }
}
