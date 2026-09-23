package com.example.server.service;

import com.example.server.config.AgentBudgetProperties;
import com.example.server.config.AgentChapterProperties;
import com.example.server.config.ChatModelProperties;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

/**
 * 动态预算入口（计划 §5.2 / D-075）：把纯函数 {@link AgentBudgetEstimator} 与集中配置、
 * 模型单次超时接起来，供 {@link AgentLoopService}（生效预算）与恢复扫描（判死阈值联动，
 * D-076）共用同一套推导，禁止两处各自拼一套公式。
 */
@Service
public class AgentBudgetService {

    /** 中文文本的字符/Token 粗估（1 个 Token ≈ 1.5 个中文字符）。 */
    private static final double CHARS_PER_TOKEN = 1.5;

    private final AgentBudgetProperties properties;
    private final AgentChapterProperties chapterProperties;
    private final long modelCallTimeoutMs;

    public AgentBudgetService(AgentBudgetProperties properties,
                              AgentChapterProperties chapterProperties,
                              ChatModelProperties chatProperties) {
        this.properties = properties;
        this.chapterProperties = chapterProperties;
        this.modelCallTimeoutMs = Math.max(1, chatProperties.getTimeoutSeconds()) * 1000L;
    }

    public AgentBudgetEstimator.Settings settings() {
        return new AgentBudgetEstimator.Settings(
                chapterProperties.getBatchSize(),
                properties.getMaxRounds(),
                properties.getMaxDurationMs(),
                properties.getHardMaxDurationMs(),
                properties.getMaxEstimatedTokens(),
                properties.getHardMaxEstimatedTokens(),
                properties.getTokensPerVideoSecondFloor(),
                properties.getSafetyFactor());
    }

    /** 成本预算（0 = 不启用），供 AgentLoopService 的 checkBudget 使用。 */
    public double maxEstimatedCost() {
        return properties.getMaxEstimatedCost();
    }

    /** 按完整 Context 推导生效预算（在任何模型调用前一次性计算）。 */
    public AgentBudgetEstimator.Estimate estimate(VideoContext context) {
        return AgentBudgetEstimator.estimate(new AgentBudgetEstimator.Input(
                effectiveDurationMs(context),
                context == null || context.chapters() == null ? 0 : context.chapters().size(),
                estimateInputTokensPerBatch(context),
                modelCallTimeoutMs,
                settings()));
    }

    /** 单批输入 Token 粗估：上下文批量字符上限（见 {@code context-max-chars}）/ 1.5。 */
    public long estimateInputTokensPerBatch(VideoContext context) {
        long chars = 0;
        if (context != null) {
            for (VideoContext.VideoSegment segment : context.segments()) {
                chars += segment.transcript().length();
                for (String ocr : segment.ocrTexts()) {
                    chars += ocr.length();
                }
            }
        }
        long batchChars = Math.min(chars, properties.getContextMaxChars());
        return Math.max(1, (long) Math.ceil(batchChars / CHARS_PER_TOKEN));
    }

    /** 推导用的视频时长：缺省时长时以最后片段结束时间为准。 */
    public static long effectiveDurationMs(VideoContext context) {
        if (context == null) return 1;
        if (context.durationMs() != null && context.durationMs() > 0) return context.durationMs();
        return context.segments().isEmpty()
                ? 1
                : context.segments().get(context.segments().size() - 1).endMs();
    }

    /**
     * 恢复扫描判死阈值（D-076 修订，2026-09-20 真实 30 分钟样本校准）：
     * {@code max(配置阈值, 2 × 模型单次超时 + 60s, 视频时长 / 2)}。
     *
     * <p>检查点只在阶段边界写：单个阶段最坏 ≈ 一次模型调用超时（300s），所以按"2 × 单次超时 +
     * 余量"兜住阶段级静默；视频时长 / 2 兜住"首次上下文落盘前的整段 ASR"（30 分钟视频实测 232s，
     * 但长视频 ASR 随时长线性增长）。不再用"2 × 总执行预算"——那会把 30 分钟视频的僵尸发现
     * 推迟到 70 分钟，崩溃恢复体验不可接受。
     */
    public long recoveryStaleThresholdSeconds(Long durationMs, long configuredSeconds) {
        long perStage = 2 * (modelCallTimeoutMs / 1000) + 60;
        long byDuration = durationMs == null || durationMs <= 0
                ? 0 : (long) Math.ceil(durationMs / 1000.0 / 2);
        return Math.max(Math.max(configuredSeconds, perStage), byDuration);
    }
}
