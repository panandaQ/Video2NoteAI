package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.knowledge.KnowledgeConversationStatus;
import com.example.server.dto.knowledge.KnowledgeConversationHotProjection;
import com.example.server.dto.knowledge.KnowledgeConversationResponse;
import com.example.server.dto.knowledge.KnowledgeScopeType;
import com.example.server.dto.knowledge.KnowledgeTurnResponse;
import com.example.server.dto.knowledge.KnowledgeTurnStatus;
import com.example.server.entity.KnowledgeConversation;
import com.example.server.entity.KnowledgeTurn;
import com.example.server.entity.KnowledgeTurnEvidence;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeConversationMapper;
import com.example.server.mapper.KnowledgeTurnEvidenceMapper;
import com.example.server.mapper.KnowledgeTurnMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问答只读查询契约：最近热读 Redis-first；强一致查询、旧页与回放读取 MySQL；
 * 归属校验统一 404，历史页正序返回并把 limit 收敛到 [1,50]。
 */
class KnowledgeConversationQueryServiceTest {

    private static final Long USER_ID = 7L;

    private final KnowledgeConversationMapper conversationMapper = mock(KnowledgeConversationMapper.class);
    private final KnowledgeTurnMapper turnMapper = mock(KnowledgeTurnMapper.class);
    private final KnowledgeTurnEvidenceMapper evidenceMapper = mock(KnowledgeTurnEvidenceMapper.class);
    private final KnowledgeQuestionProperties properties = new KnowledgeQuestionProperties();
    private final KnowledgeConversationHotCache hotCache = mock(KnowledgeConversationHotCache.class);
    private final KnowledgeConversationProjectionService projectionService =
            new KnowledgeConversationProjectionService(
                    conversationMapper, turnMapper, evidenceMapper, hotCache, properties);
    private final KnowledgeConversationQueryService service = new KnowledgeConversationQueryService(
            conversationMapper, turnMapper, evidenceMapper, properties, projectionService);

    @Test
    void foreignOrMissingConversationIsUniform404() {
        when(conversationMapper.findOwnedById(99L, USER_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.conversationHeader(USER_ID, 99L));
    }

    @Test
    void conversationHeaderProjectsScopeAndVersion() {
        KnowledgeConversation conversation = conversation();
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(conversation);

        KnowledgeConversationResponse header = service.conversationHeader(USER_ID, 91L);

        assertEquals(91L, header.conversationId());
        assertEquals(KnowledgeScopeType.SINGLE_VIDEO, header.scopeType());
        assertEquals(27L, header.scopeMediaId());
        assertEquals(3L, header.version());
        assertEquals(4, header.lastTurnNo());
    }

    @Test
    void hotConversationHeaderAvoidsMysqlRead() {
        KnowledgeConversationResponse header = header(3L, 4);
        when(hotCache.find(USER_ID, 91L, 3L)).thenReturn(Optional.of(
                new KnowledgeConversationHotProjection(USER_ID, header, List.of())));

        assertEquals(header, service.conversationHeader(USER_ID, 91L, 3L));

        verify(conversationMapper, never()).findOwnedById(91L, USER_ID);
        verify(turnMapper, never()).findPageBefore(eq(91L), eq(USER_ID), eq(null), anyInt());
    }

    @Test
    void turnBelongingToAnotherConversationIs404() {
        KnowledgeTurn turn = turn(314L, 92L, KnowledgeTurnStatus.COMPLETED);
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(turn);

        assertThrows(BusinessException.class, () -> service.turn(USER_ID, 91L, 314L));
        assertThrows(BusinessException.class, () -> service.requireOwnedTurn(USER_ID, 91L, 314L));
    }

    @Test
    void turnsPageReversesToAscendingAndAttachesEvidence() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(conversation());
        KnowledgeTurn t4 = turn(314L, 91L, KnowledgeTurnStatus.COMPLETED);
        t4.setTurnNo(4);
        KnowledgeTurn t3 = turn(313L, 91L, KnowledgeTurnStatus.COMPLETED);
        t3.setTurnNo(3);
        when(turnMapper.findPageBefore(91L, USER_ID, null, 20)).thenReturn(List.of(t4, t3));
        // 服务先反转成正序再批量装载，因此查询的是 [313, 314] 而非取页顺序。
        when(evidenceMapper.findByTurnIds(List.of(313L, 314L))).thenReturn(List.of(evidence(314L, 1)));

        List<KnowledgeTurnResponse> turns = service.turns(USER_ID, 91L, null, null);

        assertEquals(List.of(3, 4), turns.stream().map(KnowledgeTurnResponse::turnNo).toList());
        assertEquals(1, turns.get(1).evidence().size());
        assertEquals(1, turns.get(1).evidence().get(0).rank());
    }

    @Test
    void turnsPageClampsLimitToConfiguredBounds() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(conversation());
        when(turnMapper.findPageBefore(91L, USER_ID, 5, 50)).thenReturn(List.of());

        service.turns(USER_ID, 91L, 5, 999);

        verify(turnMapper).findPageBefore(eq(91L), eq(USER_ID), eq(5), eq(50));

        when(turnMapper.findPageBefore(91L, USER_ID, null, 20)).thenReturn(List.of());
        service.turns(USER_ID, 91L, null, 0);
        verify(turnMapper).findPageBefore(eq(91L), eq(USER_ID), eq(null), eq(20));
    }

    @Test
    void currentEventProjectsTerminalAndProcessingStates() {
        KnowledgeTurn completed = turn(314L, 91L, KnowledgeTurnStatus.COMPLETED);
        KnowledgeTurn processing = turn(315L, 91L, KnowledgeTurnStatus.PROCESSING);
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(completed);
        when(turnMapper.findOwnedById(315L, USER_ID)).thenReturn(processing);

        TaskEvent completedEvent = service.currentEvent(USER_ID, 91L, 314L);
        assertEquals(TaskStatus.State.COMPLETED, completedEvent.state());

        TaskEvent processingEvent = service.currentEvent(USER_ID, 91L, 315L);
        assertEquals(TaskStatus.State.PROCESSING, processingEvent.state());
    }

    @Test
    void loadHistoryMapsOnlyCompletedTurnsInOrder() {
        KnowledgeTurn t2 = turn(312L, 91L, KnowledgeTurnStatus.COMPLETED);
        t2.setTurnNo(2);
        t2.setAnswerMode("VIDEO_GROUNDED");
        when(turnMapper.findRecentCompleted(91L, USER_ID, 10)).thenReturn(List.of(t2));

        var history = service.loadHistory(91L, USER_ID);

        assertEquals(1, history.size());
        assertEquals(2, history.get(0).turnNo());
        verify(evidenceMapper, never()).findByTurnIds(anyList());
        verify(turnMapper).findRecentCompleted(91L, USER_ID, 10);
    }

    @Test
    void modelHistoryUsesOnlyExactVersionHotProjection() {
        KnowledgeTurnResponse completed = new KnowledgeTurnResponse(
                314L, 4, "req-314", "问题", "改写", KnowledgeTurnStatus.COMPLETED,
                "VIDEO_GROUNDED", "回答", null, List.of(), LocalDateTime.now(), LocalDateTime.now());
        when(hotCache.findExact(USER_ID, 91L, 3L)).thenReturn(Optional.of(
                new KnowledgeConversationHotProjection(USER_ID, header(3L, 4), List.of(completed))));

        var history = service.loadHistory(91L, USER_ID, 3L);

        assertEquals(List.of(4), history.stream().map(item -> item.turnNo()).toList());
        verify(turnMapper, never()).findRecentCompleted(91L, USER_ID, 10);
    }

    @Test
    void conversationListUsesOwnedMysqlCursorPage() {
        when(conversationMapper.findOwnedPageBefore(USER_ID, 100L, 20))
                .thenReturn(List.of(conversation()));

        var page = service.conversations(USER_ID, 100L, null);

        assertEquals(List.of(91L), page.stream().map(KnowledgeConversationResponse::conversationId).toList());
        verify(conversationMapper).findOwnedPageBefore(USER_ID, 100L, 20);
    }

    @Test
    void conversationListCanBeFilteredByOwnedMedia() {
        when(conversationMapper.findOwnedMediaPage(USER_ID, 27L, 20))
                .thenReturn(List.of(conversation()));

        var page = service.conversations(USER_ID, null, null, 27L);

        assertEquals(List.of(91L), page.stream().map(KnowledgeConversationResponse::conversationId).toList());
        verify(conversationMapper).findOwnedMediaPage(USER_ID, 27L, 20);
    }

    private KnowledgeConversationResponse header(long version, int lastTurnNo) {
        return new KnowledgeConversationResponse(
                91L, KnowledgeScopeType.SINGLE_VIDEO, 27L, "标题",
                KnowledgeConversationStatus.ACTIVE, version, lastTurnNo,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private KnowledgeConversation conversation() {
        KnowledgeConversation conversation = new KnowledgeConversation();
        conversation.setId(91L);
        conversation.setUserId(USER_ID);
        conversation.setScopeType(KnowledgeScopeType.SINGLE_VIDEO);
        conversation.setScopeMediaId(27L);
        conversation.setTitle("标题");
        conversation.setStatus(KnowledgeConversationStatus.ACTIVE);
        conversation.setVersion(3L);
        conversation.setLastTurnNo(4);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private KnowledgeTurn turn(Long id, Long conversationId, KnowledgeTurnStatus status) {
        KnowledgeTurn turn = new KnowledgeTurn();
        turn.setId(id);
        turn.setUserId(USER_ID);
        turn.setConversationId(conversationId);
        turn.setTurnNo(1);
        turn.setRequestId("req-" + id);
        turn.setQuestion("问题");
        turn.setStatus(status);
        turn.setAnswer("回答");
        turn.setCreatedAt(LocalDateTime.now());
        return turn;
    }

    private KnowledgeTurnEvidence evidence(Long turnId, int rank) {
        KnowledgeTurnEvidence evidence = new KnowledgeTurnEvidence();
        evidence.setId(1L);
        evidence.setTurnId(turnId);
        evidence.setEvidenceRank(rank);
        evidence.setMediaId(27L);
        evidence.setTitleSnapshot("标题快照");
        evidence.setStartMs(1000L);
        evidence.setEndMs(5000L);
        evidence.setSource("ASR");
        evidence.setSnippet("片段");
        return evidence;
    }
}
