package com.example.server.service.knowledge;

import com.example.server.entity.KnowledgeConversation;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeConversationMapper;
import com.example.server.mapper.KnowledgeTurnEvidenceMapper;
import com.example.server.mapper.KnowledgeTurnMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeConversationLifecycleServiceTest {

    private final KnowledgeConversationMapper conversationMapper = mock(KnowledgeConversationMapper.class);
    private final KnowledgeTurnMapper turnMapper = mock(KnowledgeTurnMapper.class);
    private final KnowledgeTurnEvidenceMapper evidenceMapper = mock(KnowledgeTurnEvidenceMapper.class);
    private final KnowledgeConversationProjectionService projectionService =
            mock(KnowledgeConversationProjectionService.class);
    private final KnowledgeConversationLifecycleService service = new KnowledgeConversationLifecycleService(
            conversationMapper, turnMapper, evidenceMapper, projectionService);

    @Test
    void deleteOwnedRemovesEvidenceTurnsConversationThenCache() {
        KnowledgeConversation conversation = new KnowledgeConversation();
        conversation.setId(91L);
        when(conversationMapper.findOwnedById(91L, 7L)).thenReturn(conversation);
        when(turnMapper.findIdsByConversationId(91L)).thenReturn(List.of(314L, 315L));

        service.deleteOwned(7L, 91L);

        verify(evidenceMapper).deleteByTurnIds(List.of(314L, 315L));
        verify(turnMapper).deleteByConversationId(91L, 7L);
        verify(conversationMapper).deleteOwnedById(91L, 7L);
        verify(projectionService).evictAfterCommit(7L, 91L);
    }

    @Test
    void foreignConversationIs404AndDoesNotDelete() {
        assertThrows(BusinessException.class, () -> service.deleteOwned(7L, 99L));
        verify(turnMapper, never()).findIdsByConversationId(99L);
    }

    @Test
    void mediaDeletionRemovesAllOwnedSingleVideoConversations() {
        when(conversationMapper.findIdsByOwnedMedia(7L, 27L)).thenReturn(List.of(91L, 92L));
        when(turnMapper.findIdsByConversationId(91L)).thenReturn(List.of());
        when(turnMapper.findIdsByConversationId(92L)).thenReturn(List.of(400L));

        service.beforeMediaDeleted(27L, 7L);

        verify(conversationMapper).deleteOwnedById(91L, 7L);
        verify(conversationMapper).deleteOwnedById(92L, 7L);
        verify(evidenceMapper).deleteByTurnIds(List.of(400L));
        verify(projectionService).evictAfterCommit(7L, 91L);
        verify(projectionService).evictAfterCommit(7L, 92L);
    }
}
