package com.example.server.dto.knowledge;

import java.util.List;

/**
 * Redis 中的用户私有热会话投影。
 *
 * <p>MySQL 仍保存全部会话事实；本投影只携带会话头与最近有界轮次。userId 显式进入
 * payload，用于在读取时防御错误 Key 或污染数据，不能仅依赖 Redis Key 做隔离。
 */
public record KnowledgeConversationHotProjection(
        Long userId,
        KnowledgeConversationResponse conversation,
        List<KnowledgeTurnResponse> recentTurns
) {
    public KnowledgeConversationHotProjection {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }

    public long version() {
        return conversation == null ? -1 : conversation.version();
    }

    public int lastTurnNo() {
        return conversation == null ? -1 : conversation.lastTurnNo();
    }
}
