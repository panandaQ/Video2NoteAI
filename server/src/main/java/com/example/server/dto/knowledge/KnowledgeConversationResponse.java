package com.example.server.dto.knowledge;

import java.time.LocalDateTime;

/**
 * 会话头（runbook §5.4）：直查 MySQL，不经过缓存；{@code version} 回传给客户端
 * 用于判断本地内存态是否过期，本切片不做缓存版本协商。
 */
public record KnowledgeConversationResponse(
        Long conversationId,
        KnowledgeScopeType scopeType,
        Long scopeMediaId,
        String title,
        KnowledgeConversationStatus status,
        long version,
        int lastTurnNo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
