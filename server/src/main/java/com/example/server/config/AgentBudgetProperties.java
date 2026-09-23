package com.example.server.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Agent 预算配置（计划 §4.1/§5.2，D-075）。
 *
 * <p>现有 {@code max-*} 键保留为推导公式的默认下限，新增 {@code hard-*} 才是真正的全局硬上限，
 * 避免同一个变量同时充当上下限。集中到 {@code @ConfigurationProperties}，
 * 不继续在业务 Service 中增加散落的 {@code @Value}。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "agent.budget")
public class AgentBudgetProperties {

    /** Agent 最大轮数（保持 2 不变；章节数量影响 batch 数与预算，不靠减轮数省钱）。 */
    @Min(1)
    private int maxRounds = 2;

    /** 时长预算默认下限（历史键，曾作硬上限；D-075 起改作下限）。 */
    @Min(1)
    private long maxDurationMs = 120_000L;

    /** 时长预算全局硬上限。 */
    @Min(1)
    private long hardMaxDurationMs = 3_600_000L;

    /** Token 预算默认下限（历史键，语义迁移同上）。 */
    @Min(1)
    private long maxEstimatedTokens = 50_000L;

    /** Token 预算全局硬上限（D-075：400k；用户授权"token 可以大一点"，30 分钟两轮章节化执行实测需 >200k）。 */
    @Min(1)
    private long hardMaxEstimatedTokens = 400_000L;

    /** 0 = 不启用成本预算。 */
    @Min(0)
    private double maxEstimatedCost = 0;

    /** 视频每秒的 Token 下限基数：30 分钟样本 = 1800×150 = 270,000，满足 ≥150,000 验收锚点。 */
    @Min(1)
    private long tokensPerVideoSecondFloor = 150L;

    /** 推导值安全系数，≥ 1。 */
    @DecimalMin("1.0")
    private double safetyFactor = 1.25;

    /**
     * 单次模型调用的上下文字符上限：长视频裁剪与单批 Token 估算共用同一值，避免两处各自写死漂移。
     * 由 24,000 提升到 96,000（约 64k Token），否则 Executor 只能看到开头/结尾的摘要性章节，
     * 中间的技术核心章节（模型/实验）被 24k 裁剪饿死，笔记退化为元评论。
     */
    @Min(1)
    private int contextMaxChars = 96_000;

    @AssertTrue(message = "agent.budget.max-duration-ms 不得超过 hard-max-duration-ms")
    public boolean isDurationBoundsValid() {
        return maxDurationMs <= hardMaxDurationMs;
    }

    @AssertTrue(message = "agent.budget.max-estimated-tokens 不得超过 hard-max-estimated-tokens")
    public boolean isTokenBoundsValid() {
        return maxEstimatedTokens <= hardMaxEstimatedTokens;
    }
}
