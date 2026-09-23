package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.knowledge.KnowledgeConversationHotProjection;
import com.example.server.dto.knowledge.KnowledgeConversationResponse;
import com.example.server.dto.knowledge.KnowledgeEvidenceResponse;
import com.example.server.dto.knowledge.KnowledgeTurnResponse;
import com.example.server.entity.KnowledgeConversation;
import com.example.server.entity.KnowledgeTurn;
import com.example.server.entity.KnowledgeTurnEvidence;
import com.example.server.mapper.KnowledgeConversationMapper;
import com.example.server.mapper.KnowledgeTurnEvidenceMapper;
import com.example.server.mapper.KnowledgeTurnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MySQL 会话事实到 Redis 热投影的唯一装配入口。
 *
 * <p>它只负责可重建投影；命令、权限和单轮终态读取不依赖本类。事务内调用
 * {@link #refreshAfterCommit(Long, Long)} 时只注册提交后动作，避免缓存先于事实可见。
 */
@Service
public class KnowledgeConversationProjectionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConversationProjectionService.class);

    private final KnowledgeConversationMapper conversationMapper;
    private final KnowledgeTurnMapper turnMapper;
    private final KnowledgeTurnEvidenceMapper evidenceMapper;
    private final KnowledgeConversationHotCache hotCache;
    private final KnowledgeQuestionProperties properties;

    public KnowledgeConversationProjectionService(KnowledgeConversationMapper conversationMapper,
                                                  KnowledgeTurnMapper turnMapper,
                                                  KnowledgeTurnEvidenceMapper evidenceMapper,
                                                  KnowledgeConversationHotCache hotCache,
                                                  KnowledgeQuestionProperties properties) {
        this.conversationMapper = conversationMapper;
        this.turnMapper = turnMapper;
        this.evidenceMapper = evidenceMapper;
        this.hotCache = hotCache;
        this.properties = properties;
    }

    public Optional<KnowledgeConversationHotProjection> findOrLoad(Long userId,
                                                                   Long conversationId,
                                                                   Long knownVersion) {
        Optional<KnowledgeConversationHotProjection> cached = hotCache.find(
                userId, conversationId, knownVersion);
        if (cached.isPresent()) {
            return cached;
        }
        return loadFromMysql(userId, conversationId).map(projection -> {
            hotCache.remember(projection);
            return projection;
        });
    }

    /** 模型历史：缓存必须与受理命令持有的 expectedVersion 完全一致，否则回 MySQL。 */
    public Optional<KnowledgeConversationHotProjection> findExact(Long userId,
                                                                  Long conversationId,
                                                                  long expectedVersion) {
        return hotCache.findExact(userId, conversationId, expectedVersion);
    }

    public void refreshNow(Long userId, Long conversationId) {
        try {
            Optional<KnowledgeConversationHotProjection> projection = loadFromMysql(userId, conversationId);
            if (projection.isPresent()) {
                hotCache.remember(projection.get());
            } else {
                hotCache.evict(userId, conversationId);
            }
        } catch (RuntimeException e) {
            log.warn("knowledge_hot_projection_refresh_failed userId={} conversationId={}",
                    userId, conversationId);
        }
    }

    public void refreshAfterCommit(Long userId, Long conversationId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshNow(userId, conversationId);
                }
            });
        } else {
            refreshNow(userId, conversationId);
        }
    }

    public void evict(Long userId, Long conversationId) {
        hotCache.evict(userId, conversationId);
    }

    public void evictAfterCommit(Long userId, Long conversationId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(userId, conversationId);
                }
            });
        } else {
            evict(userId, conversationId);
        }
    }

    private Optional<KnowledgeConversationHotProjection> loadFromMysql(Long userId, Long conversationId) {
        KnowledgeConversation conversation = conversationMapper.findOwnedById(conversationId, userId);
        if (conversation == null) {
            return Optional.empty();
        }
        int limit = properties.getConversation().getHotMaxTurns();
        List<KnowledgeTurn> descending = turnMapper.findPageBefore(conversationId, userId, null, limit);
        List<KnowledgeTurn> ascending = new ArrayList<>(descending);
        Collections.reverse(ascending);
        return Optional.of(new KnowledgeConversationHotProjection(
                userId, toHeader(conversation), composeTurns(ascending)));
    }

    KnowledgeConversationResponse toHeader(KnowledgeConversation conversation) {
        return new KnowledgeConversationResponse(
                conversation.getId(), conversation.getScopeType(), conversation.getScopeMediaId(),
                conversation.getTitle(), conversation.getStatus(),
                conversation.getVersion() == null ? 0 : conversation.getVersion(),
                conversation.getLastTurnNo() == null ? 0 : conversation.getLastTurnNo(),
                conversation.getCreatedAt(), conversation.getUpdatedAt());
    }

    List<KnowledgeTurnResponse> composeTurns(List<KnowledgeTurn> turns) {
        if (turns.isEmpty()) {
            return List.of();
        }
        List<Long> turnIds = turns.stream().map(KnowledgeTurn::getId).toList();
        Map<Long, List<KnowledgeTurnEvidence>> evidenceByTurn =
                evidenceMapper.findByTurnIds(turnIds).stream()
                        .collect(Collectors.groupingBy(KnowledgeTurnEvidence::getTurnId));
        return turns.stream()
                .map(turn -> composeTurn(turn, evidenceByTurn.getOrDefault(turn.getId(), List.of())))
                .toList();
    }

    KnowledgeTurnResponse composeTurn(KnowledgeTurn turn, List<KnowledgeTurnEvidence> evidence) {
        List<KnowledgeEvidenceResponse> evidenceView = evidence.stream()
                .map(row -> new KnowledgeEvidenceResponse(
                        row.getEvidenceRank(), row.getMediaId(), row.getTitleSnapshot(),
                        row.getStartMs(), row.getEndMs(), row.getSource(), row.getSnippet(), row.getScore()))
                .toList();
        return new KnowledgeTurnResponse(
                turn.getId(), turn.getTurnNo(), turn.getRequestId(), turn.getQuestion(),
                turn.getRewrittenQuery(), turn.getStatus(), turn.getAnswerMode(), turn.getAnswer(),
                turn.getErrorCode(), evidenceView, turn.getCreatedAt(), turn.getCompletedAt());
    }
}
