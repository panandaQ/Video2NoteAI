package com.example.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 知识问答的容量、超时与评测参数（runbook §11）。
 *
 * <p>集中在一个 {@code @ConfigurationProperties} 中，禁止在业务类里散落 {@code @Value}。
 * 所有项都有明确默认值；非法值在启动期即失败，不静默使用危险默认。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeQuestionProperties {

    @Valid
    private final Question question = new Question();

    @Valid
    private final Retrieval retrieval = new Retrieval();

    @Valid
    private final Conversation conversation = new Conversation();

    /** {@code knowledge.question.*}：问题受理、僵尸收敛与幂等映射参数。 */
    @Data
    public static class Question {

        /** 归一化后的问题长度上限（与 knowledge_turns.question 列宽一致）。 */
        @Min(1)
        private int maxLength = 1000;

        /** 僵尸轮次判死阈值（秒）：必须明显大于问答总超时，否则会把慢回答误判成中断。 */
        @Min(1)
        private int processingStaleSeconds = 300;

        /** 单次问答的执行总超时（秒）：通过 AgentExecutionBudget 作用于检索与模型调用的整条执行链。 */
        @Min(1)
        private int executionTimeoutSeconds = 240;

        /** 僵尸恢复扫描的固定间隔（毫秒）。 */
        @Min(1)
        private long recoveryFixedDelayMs = 60_000;

        /** 恢复扫描每轮处理的最大僵尸轮次数。 */
        @Min(1)
        private int recoveryBatchSize = 100;

        /** requestId → {conversationId}:{turnId} 幂等映射的 TTL（小时）。 */
        @Min(1)
        private int requestTtlHours = 24;

        /** 参与追问消歧的最近已完成轮次上限。 */
        @Min(1)
        private int historyMaxTurns = 10;

        /** 判死阈值必须大于执行总超时：僵尸判定只能命中真正死亡的轮次，健康慢回答不被误杀。 */
        public boolean isStaleWindowValid() {
            return processingStaleSeconds > executionTimeoutSeconds;
        }
    }

    /** {@code knowledge.retrieval.*}：检索与回答参数。 */
    @Data
    public static class Retrieval {

        /** 交给回答模型的最大证据条数（服务端分配 E1..En 的 n 上限）。 */
        @Min(1)
        private int maxEvidence = 8;
    }

    /** {@code knowledge.conversation.*}：Redis 热会话投影的容量与生命周期。 */
    @Data
    public static class Conversation {

        /** 最近访问会话的滑动 TTL（分钟）。 */
        @Min(1)
        private int hotTtlMinutes = 30;

        /** 每个热投影最多携带的最近轮次数。 */
        @Min(1)
        private int hotMaxTurns = 10;

        /** 单个热投影序列化后的最大字节数。 */
        @Min(1024)
        private int hotMaxBytes = 65_536;
    }
}
