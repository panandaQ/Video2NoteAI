package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.server.dto.knowledge.KnowledgeTurnStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个问答轮次：同一行表达问题、回答与生命周期（D-084）。
 *
 * <p>{@code requestId} 在用户维度唯一（{@code uk_knowledge_turn_request}），是客户端重试的幂等键；
 * {@code turnNo} 在会话内严格递增（{@code uk_knowledge_turn_order}）。{@code userId} 是为用户维度
 * 幂等查询与隔离而有意冗余——按 requestId 查询必须同时带 userId。
 *
 * <p>{@code scope_fingerprint} 是本轮允许媒体 ID 集合的 SHA-256，仅用于审计，不是权限凭证；
 * {@code rewrittenQuery/retrievalMode/retrievedCount/citedCount/durationMs} 是评测诊断字段。
 */
@Data
@TableName("knowledge_turns")
public class KnowledgeTurn {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long conversationId;
    private Integer turnNo;

    private String requestId;
    /** 内部运维字段：受理时生成，贯穿改写、检索、回答、校验、落库与通知；API 不回传。 */
    private String traceId;

    private String question;
    private String rewrittenQuery;

    private KnowledgeTurnStatus status;

    /** 来源模式枚举名（VIDEO_GROUNDED / HYBRID / MODEL_KNOWLEDGE）；处理中为 null。 */
    private String answerMode;
    /** 本轮是否检索到校验通过的视频引用（VIDEO_GROUNDED/HYBRID 为 true，MODEL_KNOWLEDGE 为 false）。 */
    private Boolean videoEvidenceFound;
    private String answer;

    private Integer scopeMediaCount;
    private String scopeFingerprint;

    private String retrievalMode;
    private Integer retrievedCount;
    private Integer citedCount;
    private Long durationMs;

    /** 失败时的受控错误码（{@code KnowledgeErrorCode.code()}）；成功时为 null。 */
    private String errorCode;

    private LocalDateTime createdAt;
    /** 进入终态（COMPLETED/FAILED）的时间；处理中为 null。 */
    private LocalDateTime completedAt;
}
