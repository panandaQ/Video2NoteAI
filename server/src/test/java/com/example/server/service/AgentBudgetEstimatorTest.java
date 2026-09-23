package com.example.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 30 分钟预算公式契约（计划 §1.3 / §5.2 / §7.1，D-075）。
 */
class AgentBudgetEstimatorTest {

    private static AgentBudgetEstimator.Settings settings() {
        return new AgentBudgetEstimator.Settings(
                8,                    // chapterBatchSize
                2,                    // maxRounds（保持不变）
                120_000L,             // defaultMaxDurationMs（现有键 → 默认下限）
                3_600_000L,           // hardMaxDurationMs
                50_000L,              // defaultMaxEstimatedTokens（现有键 → 默认下限）
                300_000L,             // hardMaxEstimatedTokens（D-075）
                90L,                  // tokensPerVideoSecondFloor
                1.25);                // safetyFactor
    }

    @Test
    void thirtyMinuteVideoDerivesAtLeast150kTokens() {
        AgentBudgetEstimator.Estimate estimate = AgentBudgetEstimator.estimate(
                new AgentBudgetEstimator.Input(30 * 60 * 1000L, 0, 16_000L, 300_000L, settings()));
        // 1800s × 90 = 162,000 → ×1.25 = 202,500，不受 200k/50k 边界影响
        assertEquals(202_500L, estimate.derivedTokens());
        assertEquals(202_500L, estimate.effectiveTokenBudget());
        assertTrue(estimate.derivedTokens() >= 150_000L, "30 分钟视频推导预算不少于 150,000");
    }

    @Test
    void shortVideoKeepsDefaultLowerBound() {
        AgentBudgetEstimator.Estimate estimate = AgentBudgetEstimator.estimate(
                new AgentBudgetEstimator.Input(30_000L, 0, 1_000L, 300_000L, settings()));
        assertEquals(50_000L, estimate.effectiveTokenBudget(), "短视频不得低于现有默认下限");
    }

    @Test
    void chapterBatchesIncreaseCallCountAndDurationBudget() {
        AgentBudgetEstimator.Estimate estimate = AgentBudgetEstimator.estimate(
                new AgentBudgetEstimator.Input(30 * 60 * 1000L, 12, 16_000L, 300_000L, settings()));
        assertEquals(2, estimate.chapterBatches(), "12 章 / batch 8 = 2 批");
        assertEquals(11L, estimate.expectedCalls(), "1 + 2×2×2 + 1 + 1 = 11");
        assertEquals(11L * 300_000L, estimate.effectiveDurationMs());
    }

    @Test
    void hardCapsBindForExtremeVideos() {
        AgentBudgetEstimator.Estimate estimate = AgentBudgetEstimator.estimate(
                new AgentBudgetEstimator.Input(3 * 3600_000L, 100, 50_000L, 300_000L, settings()));
        assertEquals(300_000L, estimate.effectiveTokenBudget(), "Token 硬上限兜底");
        assertEquals(3_600_000L, estimate.effectiveDurationMs(), "时长硬上限兜底");
    }

    @Test
    void zeroChaptersStillYieldsOneBatch() {
        AgentBudgetEstimator.Estimate estimate = AgentBudgetEstimator.estimate(
                new AgentBudgetEstimator.Input(60_000L, 0, 16_000L, 300_000L, settings()));
        assertEquals(1, estimate.chapterBatches());
        assertEquals(7L, estimate.expectedCalls());
    }

    @Test
    void invalidSettingsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () ->
                new AgentBudgetEstimator.Settings(0, 2, 1L, 1L, 1L, 1L, 1L, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new AgentBudgetEstimator.Settings(8, 2, 120_000L, 3_600_000L, 50_000L, 40_000L, 90L, 1.25));
        assertThrows(IllegalArgumentException.class, () ->
                new AgentBudgetEstimator.Settings(8, 2, 120_000L, 3_600_000L, 50_000L, 300_000L, 90L, 0.9));
        assertThrows(IllegalArgumentException.class, () ->
                new AgentBudgetEstimator.Settings(8, 2, 3_600_000L, 120_000L, 50_000L, 300_000L, 90L, 1.25));
    }

    @Test
    void invalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentBudgetEstimator.estimate(
                        new AgentBudgetEstimator.Input(-1L, 0, 1_000L, 300_000L, settings())));
        assertThrows(IllegalArgumentException.class, () ->
                AgentBudgetEstimator.estimate(
                        new AgentBudgetEstimator.Input(60_000L, 0, 1_000L, 0L, settings())));
    }
}
