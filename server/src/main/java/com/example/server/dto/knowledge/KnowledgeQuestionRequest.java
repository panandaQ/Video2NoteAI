package com.example.server.dto.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提问请求（runbook §5.1）：首问携带 {@code scope}，追问携带 {@code conversationId}。
 *
 * <p>requestId 是客户端重试幂等键；question 在服务层还会做归一化与长度上限校验
 * （{@code knowledge.question.max-length}）。继续会话时携带的 scope 必须与 MySQL 固定范围一致，
 * 否则 409 {@code CONVERSATION_SCOPE_MISMATCH}。
 */
public record KnowledgeQuestionRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 36, message = "requestId 格式不正确")
        String requestId,

        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题长度不能超过 1000 个字符")
        String question,

        Long conversationId,

        @Valid
        KnowledgeScopeRequest scope
) {
}
