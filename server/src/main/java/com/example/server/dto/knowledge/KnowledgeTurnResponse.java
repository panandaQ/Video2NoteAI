package com.example.server.dto.knowledge;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单个轮次（runbook §5.3）：完成时包含 {@code answerMode/answer} 与本轮校验通过的引用证据；
 * 处理中 answer 为空。单轮结果与历史页共用本结构，证据总是随轮次一起返回。
 *
 * <p>{@code answerMode} 是来源模式枚举名（"VIDEO_GROUNDED" / "HYBRID" / "MODEL_KNOWLEDGE"），
 * 处理中为 null；旧的 {@code answerable} 已移除，由 {@code answerMode} 表达"可回答 + 来源"。
 */
public record KnowledgeTurnResponse(
        Long turnId,
        int turnNo,
        String requestId,
        String question,
        String rewrittenQuery,
        KnowledgeTurnStatus status,
        String answerMode,
        String answer,
        String errorCode,
        List<KnowledgeEvidenceResponse> evidence,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {
    public KnowledgeTurnResponse {
        question = question == null ? "" : question;
        rewrittenQuery = rewrittenQuery == null ? "" : rewrittenQuery;
        answer = answer == null ? "" : answer;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
