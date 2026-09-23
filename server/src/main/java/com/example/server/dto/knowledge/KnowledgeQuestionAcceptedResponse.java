package com.example.server.dto.knowledge;

/**
 * 提问受理结果（runbook §5.2）。
 *
 * <p>{@code reused=true} 表示命中了同用户同 {@code requestId} 的已有轮次（幂等回放），
 * 服务端不会再次检索或调用模型；{@code status} 为终态时表示原轮次已完成或已失败（HTTP 200），
 * {@code PROCESSING} 时表示仍在处理（HTTP 202）。{@code eventsUrl} 由 Controller 按请求上下文
 * 拼接，服务层返回时为 null。
 */
public record KnowledgeQuestionAcceptedResponse(
        Long conversationId,
        Long turnId,
        int turnNo,
        String requestId,
        KnowledgeTurnStatus status,
        long conversationVersion,
        boolean reused,
        String eventsUrl
) {

    public KnowledgeQuestionAcceptedResponse withEventsUrl(String eventsUrl) {
        return new KnowledgeQuestionAcceptedResponse(
                conversationId, turnId, turnNo, requestId, status, conversationVersion, reused, eventsUrl);
    }
}
