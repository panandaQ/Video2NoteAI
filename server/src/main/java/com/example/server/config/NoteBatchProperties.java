package com.example.server.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 默认笔记批次的输入预算。
 *
 * <p>这组限制只服务于笔记 Executor，不复用 RAG 的候选上限或旧的
 * {@code agent.budget.context-max-chars}。字符数只用于没有 tokenizer 时的保守估算，
 * 真正的装箱边界仍由 Token 预算决定。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "agent.note")
public class NoteBatchProperties {

    /** 模型声明的上下文窗口（Token）。 */
    @Min(1)
    private long modelContextTokens = 128_000L;

    /** 达到该值后，后续 Segment 从下一批开始。 */
    @Min(1)
    private long batchSoftInputTokens = 90_000L;

    /** 单次请求的保护上限；单个 Segment 也必须先被拆到此上限以内。 */
    @Min(1)
    private long batchHardInputTokens = 110_000L;

    /** 为系统提示、用户目标和模型输出保留的 Token。 */
    @Min(0)
    private long outputReserveTokens = 8_000L;

    /** 供应商和序列化误差的安全余量。 */
    @Min(0)
    private long safetyReserveTokens = 10_000L;

    /** 单个 SegmentPart 的最大字符数。 */
    @Min(1)
    private int segmentPartMaxChars = 60_000;

    /** 单媒体允许的最大批次数；超过时失败而不是静默丢弃剩余片段。 */
    @Min(1)
    private int maxBatches = 128;

    /** 批次级重试上限，供 Phase 2 Executor 使用。 */
    @Min(0)
    private int maxBatchRetries = 2;

    /** 中文/混合文本没有 tokenizer 时的保守字符/Token 估算。 */
    private double charsPerToken = 1.5d;

    @AssertTrue(message = "agent.note.batch-soft-input-tokens must not exceed batch-hard-input-tokens")
    public boolean isBatchBoundsValid() {
        return batchSoftInputTokens <= batchHardInputTokens;
    }

    @AssertTrue(message = "agent.note.batch-hard-input-tokens plus reserves must fit model-context-tokens")
    public boolean isModelWindowValid() {
        return batchHardInputTokens + outputReserveTokens + safetyReserveTokens <= modelContextTokens;
    }

    @AssertTrue(message = "agent.note.chars-per-token must be positive")
    public boolean isCharsPerTokenValid() {
        return Double.isFinite(charsPerToken) && charsPerToken > 0;
    }

    /** 实际可用于批次正文的 Token 上限。 */
    public long effectiveHardInputTokens() {
        return Math.min(batchHardInputTokens,
                Math.max(1L, modelContextTokens - outputReserveTokens - safetyReserveTokens));
    }
}
