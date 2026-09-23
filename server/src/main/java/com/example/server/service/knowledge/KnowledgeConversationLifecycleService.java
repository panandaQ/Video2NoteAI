package com.example.server.service.knowledge;

import com.example.server.common.ErrorCode;
import com.example.server.entity.KnowledgeConversation;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeConversationMapper;
import com.example.server.mapper.KnowledgeTurnEvidenceMapper;
import com.example.server.mapper.KnowledgeTurnMapper;
import com.example.server.service.MediaDeletionListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 会话显式删除与媒体删除联动；MySQL 删除提交后再清理 Redis 投影。 */
@Service
public class KnowledgeConversationLifecycleService implements MediaDeletionListener {

    private final KnowledgeConversationMapper conversationMapper;
    private final KnowledgeTurnMapper turnMapper;
    private final KnowledgeTurnEvidenceMapper evidenceMapper;
    private final KnowledgeConversationProjectionService projectionService;

    public KnowledgeConversationLifecycleService(KnowledgeConversationMapper conversationMapper,
                                                 KnowledgeTurnMapper turnMapper,
                                                 KnowledgeTurnEvidenceMapper evidenceMapper,
                                                 KnowledgeConversationProjectionService projectionService) {
        this.conversationMapper = conversationMapper;
        this.turnMapper = turnMapper;
        this.evidenceMapper = evidenceMapper;
        this.projectionService = projectionService;
    }

    @Transactional
    public void deleteOwned(Long userId, Long conversationId) {
        KnowledgeConversation conversation = conversationMapper.findOwnedById(conversationId, userId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        deleteRows(userId, conversationId);
    }

    @Override
    @Transactional
    public void beforeMediaDeleted(Long mediaId, Long userId) {
        if (mediaId == null || userId == null) {
            return;
        }
        List<Long> conversationIds = conversationMapper.findIdsByOwnedMedia(userId, mediaId);
        for (Long conversationId : conversationIds) {
            deleteRows(userId, conversationId);
        }
    }

    private void deleteRows(Long userId, Long conversationId) {
        List<Long> turnIds = turnMapper.findIdsByConversationId(conversationId);
        if (!turnIds.isEmpty()) {
            evidenceMapper.deleteByTurnIds(turnIds);
        }
        turnMapper.deleteByConversationId(conversationId, userId);
        conversationMapper.deleteOwnedById(conversationId, userId);
        projectionService.evictAfterCommit(userId, conversationId);
    }
}
