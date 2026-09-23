package com.example.server.service;

/**
 * 纯函数预算推导：在任何模型调用之前，根据完整 Context 规模与预计调用次数一次性计算
 * Agent 的有效 Token 与时长预算（计划 §5.2，D-075）。
 *
 * <p>公式：durationFloor = 视频秒数 × tokensPerVideoSecondFloor；
 * contextDemand = 单批估算输入 Token × 预计调用次数 + 输出余量；
 * derivedTokens = ceil(max(durationFloor, contextDemand) × safetyFactor)；
 * effectiveTokens = clamp(默认下限, derived, 硬上限)。
 * 时长预算 = 预计调用次数 × 模型单次超时，同样夹在默认下限与硬上限之间。
 *
 * <p>估算结果连同 derived 值进入 telemetry（{@code effectiveTokenBudget} 等），
 * 用真实样本校准 floor / safety-factor。
 */
public final class AgentBudgetEstimator {

    /** 每次模型调用固定预留的输出 Token 余量。 */
    public static final long OUTPUT_RESERVE_PER_CALL = 4_000L;

    private AgentBudgetEstimator() {
    }

    public static Estimate estimate(Input input) {
        Settings settings = input.settings();
        long videoSeconds = Math.max(1, (input.durationMs() + 999) / 1000);
        int chapterBatches = Math.max(1,
                (int) Math.ceil(input.chapterCount() / (double) settings.chapterBatchSize()));

        // 1 Planner + 每批每轮 Executor/Critic + 1 全局聚合 + 1 结构化修复余量
        long expectedCalls = 1
                + (long) chapterBatches * settings.maxRounds() * 2
                + 1
                + 1;

        long durationFloor = videoSeconds * settings.tokensPerVideoSecondFloor();
        long contextDemand = input.estimatedInputTokensPerBatch() * expectedCalls
                + expectedCalls * OUTPUT_RESERVE_PER_CALL;
        long derivedTokens = (long) Math.ceil(
                Math.max(durationFloor, contextDemand) * settings.safetyFactor());
        long effectiveTokens = clamp(settings.defaultMaxEstimatedTokens(),
                derivedTokens, settings.hardMaxEstimatedTokens());

        long derivedDurationMs = expectedCalls * input.modelCallTimeoutMs();
        long effectiveDurationMs = clamp(settings.defaultMaxDurationMs(),
                derivedDurationMs, settings.hardMaxDurationMs());

        return new Estimate(effectiveTokens, effectiveDurationMs,
                expectedCalls, chapterBatches, derivedTokens);
    }

    private static long clamp(long min, long value, long max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /** 预算推导输入：时长、章节数与单批输入规模由调用方从完整 Context 提取。 */
    public record Input(
            long durationMs,
            int chapterCount,
            long estimatedInputTokensPerBatch,
            long modelCallTimeoutMs,
            Settings settings) {
        public Input {
            if (durationMs < 0) throw new IllegalArgumentException("durationMs must be >= 0");
            if (chapterCount < 0) throw new IllegalArgumentException("chapterCount must be >= 0");
            if (estimatedInputTokensPerBatch < 0) {
                throw new IllegalArgumentException("estimatedInputTokensPerBatch must be >= 0");
            }
            if (modelCallTimeoutMs < 1) {
                throw new IllegalArgumentException("modelCallTimeoutMs must be > 0");
            }
        }
    }

    /**
     * 预算配置。现有 {@code agent.budget.max-*} 键作为默认下限，新增 {@code hard-*} 才是
     * 真正的全局硬上限（同一变量不再同时充当上下限）。
     */
    public record Settings(
            int chapterBatchSize,
            int maxRounds,
            long defaultMaxDurationMs,
            long hardMaxDurationMs,
            long defaultMaxEstimatedTokens,
            long hardMaxEstimatedTokens,
            long tokensPerVideoSecondFloor,
            double safetyFactor) {
        public Settings {
            if (chapterBatchSize < 1) throw new IllegalArgumentException("chapterBatchSize must be >= 1");
            if (maxRounds < 1) throw new IllegalArgumentException("maxRounds must be >= 1");
            if (defaultMaxDurationMs < 1 || hardMaxDurationMs < defaultMaxDurationMs) {
                throw new IllegalArgumentException("duration bounds must satisfy 0 < default <= hard");
            }
            if (defaultMaxEstimatedTokens < 1
                    || hardMaxEstimatedTokens < defaultMaxEstimatedTokens) {
                throw new IllegalArgumentException("token bounds must satisfy 0 < default <= hard");
            }
            if (tokensPerVideoSecondFloor < 1) {
                throw new IllegalArgumentException("tokensPerVideoSecondFloor must be >= 1");
            }
            if (safetyFactor < 1) throw new IllegalArgumentException("safetyFactor must be >= 1");
        }
    }

    public record Estimate(
            long effectiveTokenBudget,
            long effectiveDurationMs,
            long expectedCalls,
            int chapterBatches,
            long derivedTokens) {
    }
}
