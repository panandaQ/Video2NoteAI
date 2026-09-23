package com.example.server.service.knowledge;

import com.example.server.common.ErrorCode;
import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.knowledge.AnswerMode;
import com.example.server.dto.knowledge.KnowledgeConversationStatus;
import com.example.server.dto.knowledge.KnowledgeErrorCode;
import com.example.server.dto.knowledge.KnowledgeQuestionAcceptedResponse;
import com.example.server.dto.knowledge.KnowledgeScopeType;
import com.example.server.dto.knowledge.KnowledgeTurnStatus;
import com.example.server.entity.KnowledgeConversation;
import com.example.server.entity.KnowledgeTurn;
import com.example.server.entity.KnowledgeTurnEvidence;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeConversationMapper;
import com.example.server.mapper.KnowledgeTurnEvidenceMapper;
import com.example.server.mapper.KnowledgeTurnMapper;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.VideoImportKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Q0 契约测试：受理 / 完成 / 失败三个短事务的行为边界（runbook §12 / §13）。
 *
 * <p>固定五组契约：
 * <ul>
 *   <li>用户维度 requestId 幂等：命中回放、并发唯一键冲突回读赢家、其他用户不可探测；</li>
 *   <li>会话 CAS 单赢家：执行权与 turnNo 在同一条条件更新中取得，未命中按执行权分流；</li>
 *   <li>范围不可变：SINGLE_VIDEO 绑定后携带不一致 scope 一律 409，LIBRARY 首问稳定拒绝；</li>
 *   <li>权限隔离：媒体/会话不存在与越权统一 404，媒体未 READY 409，不泄漏状态差异；</li>
 *   <li>事务回滚不留 turnNo 空洞：轮次插入失败必须把 CAS 占用一起回滚（先 claim 后 insert、
 *       失败向上传播），完成/失败的旧线程条件更新必须 0 行丢弃。</li>
 * </ul>
 */
class KnowledgeQuestionCommandServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long MEDIA_ID = 27L;
    private static final String REQUEST_ID = "b19d07b8-0a36-4fbd-a22c-a1bc0df2018c";

    private final KnowledgeConversationMapper conversationMapper = mock(KnowledgeConversationMapper.class);
    private final KnowledgeTurnMapper turnMapper = mock(KnowledgeTurnMapper.class);
    private final KnowledgeTurnEvidenceMapper evidenceMapper = mock(KnowledgeTurnEvidenceMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final KnowledgeRequestIdCache requestCache = mock(KnowledgeRequestIdCache.class);
    private final KnowledgeQuestionEventPublisher eventPublisher = mock(KnowledgeQuestionEventPublisher.class);
    private final KnowledgeConversationProjectionService projectionService =
            mock(KnowledgeConversationProjectionService.class);

    private final KnowledgeQuestionCommandService service = new KnowledgeQuestionCommandService(
            conversationMapper, turnMapper, evidenceMapper, mediaFileMapper,
            new KnowledgeQuestionProperties(), transactionTemplate, requestCache, eventPublisher,
            projectionService);

    @BeforeEach
    void executeTransactionImmediately() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    // ---- 首问：建会话与 PROCESSING 轮次 ----

    @Test
    void firstQuestionCreatesConversationAndProcessingTurn() {
        when(mediaFileMapper.findOwnedReadyById(MEDIA_ID, USER_ID)).thenReturn(readyMedia());
        assignIdsOnInsert();

        KnowledgeQuestionAcceptedResponse result = service.accept(firstQuestion("视频中如何避免缓存击穿？"));

        ArgumentCaptor<KnowledgeConversation> conversation = ArgumentCaptor.forClass(KnowledgeConversation.class);
        verify(conversationMapper).insert(conversation.capture());
        KnowledgeConversation created = conversation.getValue();
        assertEquals(USER_ID, created.getUserId());
        assertEquals(KnowledgeScopeType.SINGLE_VIDEO, created.getScopeType());
        assertEquals(MEDIA_ID, created.getScopeMediaId());
        assertEquals("视频中如何避免缓存击穿？", created.getTitle());
        assertEquals(KnowledgeConversationStatus.ACTIVE, created.getStatus());
        assertEquals(0L, created.getVersion());
        assertEquals(1, created.getLastTurnNo());
        assertEquals(REQUEST_ID, created.getActiveRequestId());
        assertNotNull(created.getActiveRequestStartedAt());

        ArgumentCaptor<KnowledgeTurn> turn = ArgumentCaptor.forClass(KnowledgeTurn.class);
        verify(turnMapper).insert(turn.capture());
        KnowledgeTurn inserted = turn.getValue();
        assertEquals(1, inserted.getTurnNo());
        assertEquals(REQUEST_ID, inserted.getRequestId());
        assertEquals(KnowledgeTurnStatus.PROCESSING, inserted.getStatus());
        assertEquals(1, inserted.getScopeMediaCount());
        assertEquals(VideoImportKeys.sha256(String.valueOf(MEDIA_ID)), inserted.getScopeFingerprint());
        UUID.fromString(inserted.getTraceId());

        assertEquals(created.getId(), result.conversationId());
        assertEquals(inserted.getId(), result.turnId());
        assertEquals(1, result.turnNo());
        assertEquals(KnowledgeTurnStatus.PROCESSING, result.status());
        assertEquals(0L, result.conversationVersion());
        assertFalse(result.reused());
    }

    @Test
    void firstQuestionNormalizesQuestionAndTruncatesTitle() {
        when(mediaFileMapper.findOwnedReadyById(MEDIA_ID, USER_ID)).thenReturn(readyMedia());
        assignIdsOnInsert();
        String longQuestion = "问".repeat(200);

        service.accept(firstQuestion("  " + longQuestion + "  "));

        ArgumentCaptor<KnowledgeConversation> conversation = ArgumentCaptor.forClass(KnowledgeConversation.class);
        verify(conversationMapper).insert(conversation.capture());
        assertEquals(120, conversation.getValue().getTitle().length());

        ArgumentCaptor<KnowledgeTurn> turn = ArgumentCaptor.forClass(KnowledgeTurn.class);
        verify(turnMapper).insert(turn.capture());
        assertEquals(longQuestion, turn.getValue().getQuestion());
    }

    @Test
    void firstQuestionRejectsLibraryScope() {
        BusinessException e = assertBusinessError(
                () -> service.accept(new KnowledgeQuestionCommandService.SubmitCommand(
                        USER_ID, REQUEST_ID, "问题", KnowledgeScopeType.LIBRARY, null, null)),
                ErrorCode.UNPROCESSABLE, "KNOWLEDGE_SCOPE_NOT_SUPPORTED");
        assertEquals("KNOWLEDGE_SCOPE_NOT_SUPPORTED", messageCode(e));
        verify(conversationMapper, never()).insert(any(KnowledgeConversation.class));
        verify(turnMapper, never()).insert(any(KnowledgeTurn.class));
    }

    @Test
    void firstQuestionRequiresScopeAndMediaId() {
        assertBusinessError(
                () -> service.accept(new KnowledgeQuestionCommandService.SubmitCommand(
                        USER_ID, REQUEST_ID, "问题", null, null, null)),
                ErrorCode.INVALID_ARGUMENT, "首问必须指定 scope");
        assertBusinessError(
                () -> service.accept(new KnowledgeQuestionCommandService.SubmitCommand(
                        USER_ID, REQUEST_ID, "问题", KnowledgeScopeType.SINGLE_VIDEO, null, null)),
                ErrorCode.INVALID_ARGUMENT, "必须携带 mediaId");
    }

    @Test
    void firstQuestionMissingOrForeignMediaIsUniform404() {
        when(mediaFileMapper.findOwnedReadyById(MEDIA_ID, USER_ID)).thenReturn(null);
        when(mediaFileMapper.findOwnedIdById(MEDIA_ID, USER_ID)).thenReturn(null);

        assertBusinessError(() -> service.accept(firstQuestion("问题")),
                ErrorCode.NOT_FOUND, "视频不存在");
        verify(conversationMapper, never()).insert(any(KnowledgeConversation.class));
    }

    @Test
    void firstQuestionOwnedButNotReadyIs409() {
        when(mediaFileMapper.findOwnedReadyById(MEDIA_ID, USER_ID)).thenReturn(null);
        when(mediaFileMapper.findOwnedIdById(MEDIA_ID, USER_ID)).thenReturn(MEDIA_ID);

        BusinessException e = assertBusinessError(() -> service.accept(firstQuestion("问题")),
                ErrorCode.CONFLICT, "MEDIA_NOT_READY");
        assertEquals("MEDIA_NOT_READY", messageCode(e));
        verify(conversationMapper, never()).insert(any(KnowledgeConversation.class));
    }

    // ---- requestId 幂等 ----

    @Test
    void idempotentReplayReturnsExistingTurnWithoutWrites() {
        KnowledgeTurn existing = turn(314L, 91L, 4, KnowledgeTurnStatus.COMPLETED);
        when(turnMapper.findByRequestId(USER_ID, REQUEST_ID)).thenReturn(existing);
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, null, 3L, KnowledgeConversationStatus.ACTIVE));

        KnowledgeQuestionAcceptedResponse result = service.accept(firstQuestion("重复提交"));

        assertTrue(result.reused());
        assertEquals(314L, result.turnId());
        assertEquals(4, result.turnNo());
        assertEquals(KnowledgeTurnStatus.COMPLETED, result.status());
        assertEquals(3L, result.conversationVersion());
        verify(conversationMapper, never()).insert(any(KnowledgeConversation.class));
        verify(turnMapper, never()).insert(any(KnowledgeTurn.class));
        verify(conversationMapper, never()).claimExecution(anyLong(), anyLong(), anyString());
        verify(requestCache).remember(USER_ID, REQUEST_ID, 91L, 314L);
    }

    @Test
    void idempotentHitViaRedisMappingStillVerifiesAgainstMysql() {
        KnowledgeTurn existing = turn(314L, 91L, 4, KnowledgeTurnStatus.COMPLETED);
        when(requestCache.find(USER_ID, REQUEST_ID)).thenReturn(java.util.Optional.of("91:314"));
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(existing);
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, null, 3L, KnowledgeConversationStatus.ACTIVE));

        KnowledgeQuestionAcceptedResponse result = service.accept(firstQuestion("重复提交"));

        assertTrue(result.reused());
        assertEquals(314L, result.turnId());
        // 命中映射后按主键回 MySQL 校验，不再走用户维度唯一键查询。
        verify(turnMapper, never()).findByRequestId(USER_ID, REQUEST_ID);
    }

    @Test
    void idempotentMappingStaleFallsBackToUniqueKeyQuery() {
        KnowledgeTurn existing = turn(314L, 91L, 4, KnowledgeTurnStatus.COMPLETED);
        when(requestCache.find(USER_ID, REQUEST_ID)).thenReturn(java.util.Optional.of("91:314"));
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(null); // 映射过期：轮次已被清理
        when(turnMapper.findByRequestId(USER_ID, REQUEST_ID)).thenReturn(existing);
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, null, 3L, KnowledgeConversationStatus.ACTIVE));

        KnowledgeQuestionAcceptedResponse result = service.accept(firstQuestion("重复提交"));

        assertTrue(result.reused());
        verify(turnMapper).findByRequestId(USER_ID, REQUEST_ID);
    }

    @Test
    void acceptRemembersRequestMappingAfterCommit() {
        when(mediaFileMapper.findOwnedReadyById(MEDIA_ID, USER_ID)).thenReturn(readyMedia());
        assignIdsOnInsert();

        KnowledgeQuestionAcceptedResponse result = service.accept(firstQuestion("首问"));

        assertFalse(result.reused());
        verify(requestCache).remember(USER_ID, REQUEST_ID, 100L, 200L);
    }

    @Test
    void concurrentFirstQuestionDuplicateKeyReplaysWinner() {
        when(mediaFileMapper.findOwnedReadyById(MEDIA_ID, USER_ID)).thenReturn(readyMedia());
        doAnswer(inv -> {
            inv.<KnowledgeConversation>getArgument(0).setId(100L);
            return 1;
        }).when(conversationMapper).insert(any(KnowledgeConversation.class));
        when(turnMapper.insert(any(KnowledgeTurn.class))).thenThrow(new DuplicateKeyException("uk_knowledge_turn_request"));
        KnowledgeTurn winner = turn(314L, 77L, 1, KnowledgeTurnStatus.PROCESSING);
        when(turnMapper.findByRequestId(USER_ID, REQUEST_ID)).thenReturn((KnowledgeTurn) null, winner);
        KnowledgeConversation winnerConversation =
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 1, REQUEST_ID, 0L,
                        KnowledgeConversationStatus.ACTIVE);
        winnerConversation.setId(77L);
        when(conversationMapper.findOwnedById(77L, USER_ID)).thenReturn(winnerConversation);

        KnowledgeQuestionAcceptedResponse result = service.accept(firstQuestion("并发首问"));

        assertTrue(result.reused());
        assertEquals(77L, result.conversationId());
        assertEquals(314L, result.turnId());
    }

    @Test
    void duplicateKeyWithoutWinnerIsSystemError() {
        when(mediaFileMapper.findOwnedReadyById(MEDIA_ID, USER_ID)).thenReturn(readyMedia());
        doAnswer(inv -> {
            inv.<KnowledgeConversation>getArgument(0).setId(100L);
            return 1;
        }).when(conversationMapper).insert(any(KnowledgeConversation.class));
        when(turnMapper.insert(any(KnowledgeTurn.class))).thenThrow(new DuplicateKeyException("uk_knowledge_turn_request"));
        when(turnMapper.findByRequestId(USER_ID, REQUEST_ID)).thenReturn((KnowledgeTurn) null, (KnowledgeTurn) null);

        assertThrows(IllegalStateException.class, () -> service.accept(firstQuestion("并发首问")));
    }

    // ---- 追问：CAS 单赢家与顺序 ----

    @Test
    void followUpClaimsExecutionAndUsesClaimedTurnNo() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE),
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, REQUEST_ID, 3L, KnowledgeConversationStatus.ACTIVE));
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(1);
        doAnswer(inv -> {
            inv.<KnowledgeTurn>getArgument(0).setId(200L);
            return 1;
        }).when(turnMapper).insert(any(KnowledgeTurn.class));

        KnowledgeQuestionAcceptedResponse result = service.accept(followUp("第二种方案有什么代价？"));

        assertEquals(4, result.turnNo());
        assertEquals(3L, result.conversationVersion());
        assertFalse(result.reused());
        ArgumentCaptor<KnowledgeTurn> turn = ArgumentCaptor.forClass(KnowledgeTurn.class);
        verify(turnMapper).insert(turn.capture());
        assertEquals(4, turn.getValue().getTurnNo());
        assertEquals(91L, turn.getValue().getConversationId());
    }

    @Test
    void followUpBusyWhenOtherRequestHoldsLease() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE),
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, "80d17d21-0000-0000-0000-000000000000", 3L,
                        KnowledgeConversationStatus.ACTIVE));
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(0);

        BusinessException e = assertBusinessError(() -> service.accept(followUp("并发问题")),
                ErrorCode.CONFLICT, "CONVERSATION_BUSY");
        assertEquals("CONVERSATION_BUSY", messageCode(e));
        verify(turnMapper, never()).insert(any(KnowledgeTurn.class));
    }

    @Test
    void followUpReplaysWhenClaimMissButSameRequestActive() {
        KnowledgeTurn existing = turn(314L, 91L, 4, KnowledgeTurnStatus.PROCESSING);
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE),
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, REQUEST_ID, 3L, KnowledgeConversationStatus.ACTIVE));
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(0);
        when(turnMapper.findByRequestId(USER_ID, REQUEST_ID)).thenReturn((KnowledgeTurn) null, existing);

        KnowledgeQuestionAcceptedResponse result = service.accept(followUp("重试"));

        assertTrue(result.reused());
        assertEquals(314L, result.turnId());
        verify(turnMapper, never()).insert(any(KnowledgeTurn.class));
    }

    @Test
    void followUpConversationNotFoundIs404() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(null);

        assertBusinessError(() -> service.accept(followUp("问题")), ErrorCode.NOT_FOUND, "会话不存在");
        verify(conversationMapper, never()).claimExecution(anyLong(), anyLong(), anyString());
    }

    @Test
    void followUpArchivedConversationRejected() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ARCHIVED),
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ARCHIVED));
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(0);

        assertBusinessError(() -> service.accept(followUp("问题")), ErrorCode.CONFLICT, "会话状态不允许提问");
    }

    // ---- 范围不可变 ----

    @Test
    void followUpScopeMismatchRejected() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE));

        BusinessException e = assertBusinessError(() -> service.accept(
                        new KnowledgeQuestionCommandService.SubmitCommand(
                                USER_ID, REQUEST_ID, "问题", KnowledgeScopeType.SINGLE_VIDEO, 28L, 91L)),
                ErrorCode.CONFLICT, "CONVERSATION_SCOPE_MISMATCH");
        assertEquals("CONVERSATION_SCOPE_MISMATCH", messageCode(e));
        verify(conversationMapper, never()).claimExecution(anyLong(), anyLong(), anyString());
    }

    @Test
    void followUpScopeTypeMismatchRejected() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE));

        assertBusinessError(() -> service.accept(
                        new KnowledgeQuestionCommandService.SubmitCommand(
                                USER_ID, REQUEST_ID, "问题", KnowledgeScopeType.LIBRARY, null, 91L)),
                ErrorCode.CONFLICT, "CONVERSATION_SCOPE_MISMATCH");
    }

    @Test
    void followUpScopeOmittedIsAccepted() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE),
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, REQUEST_ID, 3L, KnowledgeConversationStatus.ACTIVE));
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(1);
        doAnswer(inv -> {
            inv.<KnowledgeTurn>getArgument(0).setId(200L);
            return 1;
        }).when(turnMapper).insert(any(KnowledgeTurn.class));

        KnowledgeQuestionAcceptedResponse result = service.accept(followUp("省略 scope"));

        assertFalse(result.reused());
        assertEquals(4, result.turnNo());
        verify(conversationMapper).claimExecution(91L, USER_ID, REQUEST_ID);
    }

    @Test
    void followUpMatchingScopeIsAccepted() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE),
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, REQUEST_ID, 3L, KnowledgeConversationStatus.ACTIVE));
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(1);
        doAnswer(inv -> {
            inv.<KnowledgeTurn>getArgument(0).setId(200L);
            return 1;
        }).when(turnMapper).insert(any(KnowledgeTurn.class));

        KnowledgeQuestionAcceptedResponse result = service.accept(
                new KnowledgeQuestionCommandService.SubmitCommand(
                        USER_ID, REQUEST_ID, "一致 scope", KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 91L));

        assertFalse(result.reused());
        assertEquals(4, result.turnNo());
        verify(conversationMapper).claimExecution(91L, USER_ID, REQUEST_ID);
    }

    @Test
    void followUpIncompleteScopeIsInvalidArgument() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE));

        assertBusinessError(() -> service.accept(
                        new KnowledgeQuestionCommandService.SubmitCommand(
                                USER_ID, REQUEST_ID, "问题", KnowledgeScopeType.SINGLE_VIDEO, null, 91L)),
                ErrorCode.INVALID_ARGUMENT, "必须同时指定 mediaId");
    }

    // ---- 参数校验 ----

    @Test
    void invalidRequestIdRejected() {
        assertBusinessError(() -> service.accept(
                        new KnowledgeQuestionCommandService.SubmitCommand(
                                USER_ID, "not-a-uuid", "问题", KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, null)),
                ErrorCode.INVALID_ARGUMENT, "requestId");
    }

    @Test
    void blankOrOverLongQuestionRejected() {
        assertBusinessError(() -> service.accept(firstQuestion("   ")),
                ErrorCode.INVALID_ARGUMENT, "问题不能为空");
        assertBusinessError(() -> service.accept(firstQuestion("问".repeat(1001))),
                ErrorCode.INVALID_ARGUMENT, "问题长度不能超过 1000");
    }

    // ---- 完成 / 失败 ----

    @Test
    void completeUpdatesTurnInsertsEvidenceAndReleasesLease() {
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(turn(314L, 91L, 4, KnowledgeTurnStatus.PROCESSING));
        when(turnMapper.complete(eq(314L), eq(USER_ID), eq(REQUEST_ID), any(), anyString(), anyBoolean(),
                any(), any(), any(), any(), any())).thenReturn(1);
        when(conversationMapper.releaseExecution(91L, USER_ID, REQUEST_ID)).thenReturn(1);
        KnowledgeTurnEvidence e1 = evidence(1, "片段一");
        KnowledgeTurnEvidence e2 = evidence(2, "片段二");

        boolean done = service.complete(new KnowledgeQuestionCommandService.CompletionInput(
                USER_ID, 314L, REQUEST_ID, AnswerMode.VIDEO_GROUNDED, true, "回答", "改写后的问题",
                "HYBRID", 6, 2, 1234L, List.of(e1, e2)));

        assertTrue(done);
        assertEquals(314L, e1.getTurnId());
        assertEquals(314L, e2.getTurnId());
        verify(evidenceMapper).insertBatch(314L, List.of(e1, e2));
        verify(conversationMapper).releaseExecution(91L, USER_ID, REQUEST_ID);
        verify(eventPublisher).publishAfterCommit(314L, true);
    }

    @Test
    void completeDiscardsWhenTurnMissingOrNoLongerProcessing() {
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(null);
        assertFalse(service.complete(completionInput()));

        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(turn(314L, 91L, 4, KnowledgeTurnStatus.COMPLETED));
        when(turnMapper.complete(anyLong(), anyLong(), anyString(), any(), anyString(), anyBoolean(),
                any(), any(), any(), any(), any())).thenReturn(0);
        assertFalse(service.complete(completionInput()));

        verify(evidenceMapper, never()).insertBatch(anyLong(), any());
        verify(conversationMapper, never()).releaseExecution(anyLong(), anyLong(), anyString());
        // 旧线程丢弃绝不发布：否则客户端会为一个已由别处收敛的轮次收到重复终态。
        verify(eventPublisher, never()).publishAfterCommit(anyLong(), anyBoolean());
    }

    @Test
    void completeThrowsWhenLeaseReleaseFails() {
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(turn(314L, 91L, 4, KnowledgeTurnStatus.PROCESSING));
        when(turnMapper.complete(anyLong(), anyLong(), anyString(), any(), anyString(), anyBoolean(),
                any(), any(), any(), any(), any())).thenReturn(1);
        when(conversationMapper.releaseExecution(91L, USER_ID, REQUEST_ID)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.complete(completionInput()));
    }

    @Test
    void failMarksFailedWithErrorCodeAndReleasesLease() {
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(turn(314L, 91L, 4, KnowledgeTurnStatus.PROCESSING));
        when(turnMapper.fail(314L, USER_ID, REQUEST_ID, "RETRIEVAL_UNAVAILABLE")).thenReturn(1);
        when(conversationMapper.releaseExecution(91L, USER_ID, REQUEST_ID)).thenReturn(1);

        boolean done = service.fail(new KnowledgeQuestionCommandService.FailureInput(
                USER_ID, 314L, REQUEST_ID, KnowledgeErrorCode.RETRIEVAL_UNAVAILABLE));

        assertTrue(done);
        verify(turnMapper).fail(314L, USER_ID, REQUEST_ID, "RETRIEVAL_UNAVAILABLE");
        verify(conversationMapper).releaseExecution(91L, USER_ID, REQUEST_ID);
        verify(eventPublisher).publishAfterCommit(314L, false);
    }

    @Test
    void failDiscardsWhenTurnMissingOrNoLongerProcessing() {
        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(null);
        assertFalse(service.fail(new KnowledgeQuestionCommandService.FailureInput(
                USER_ID, 314L, REQUEST_ID, KnowledgeErrorCode.ANSWER_GENERATION_FAILED)));

        when(turnMapper.findOwnedById(314L, USER_ID)).thenReturn(turn(314L, 91L, 4, KnowledgeTurnStatus.FAILED));
        when(turnMapper.fail(anyLong(), anyLong(), anyString(), anyString())).thenReturn(0);
        assertFalse(service.fail(new KnowledgeQuestionCommandService.FailureInput(
                USER_ID, 314L, REQUEST_ID, KnowledgeErrorCode.ANSWER_GENERATION_FAILED)));

        verify(conversationMapper, never()).releaseExecution(anyLong(), anyLong(), anyString());
        verify(eventPublisher, never()).publishAfterCommit(anyLong(), anyBoolean());
    }

    // ---- 回滚不留 turnNo 空洞 ----

    @Test
    void followUpTurnInsertFailurePropagatesSoTransactionRollsBackClaim() {
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, null, 3L, KnowledgeConversationStatus.ACTIVE),
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, REQUEST_ID, 3L, KnowledgeConversationStatus.ACTIVE));
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(1);
        when(turnMapper.insert(any(KnowledgeTurn.class))).thenThrow(new IllegalStateException("insert failed"));

        assertThrows(IllegalStateException.class, () -> service.accept(followUp("问题")));

        // 顺序契约：claim 成功后才插入轮次；轮次失败向上传播（真实事务中会把 CAS 占用一并回滚，
        // last_turn_no 不留下空洞）。绝不吞掉异常继续推进。
        InOrder order = org.mockito.Mockito.inOrder(conversationMapper, turnMapper);
        order.verify(conversationMapper).claimExecution(91L, USER_ID, REQUEST_ID);
        order.verify(turnMapper).insert(any(KnowledgeTurn.class));
    }

    // ---- 受理懒检查：僵尸占用收敛（runbook §6.4） ----

    @Test
    void followUpStaleOccupantIsRecoveredBeforeClaim() {
        String occupantRequestId = "80d17d21-1111-4111-8111-111111111111";
        KnowledgeConversation occupied =
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, occupantRequestId, 3L,
                        KnowledgeConversationStatus.ACTIVE);
        occupied.setActiveRequestStartedAt(LocalDateTime.now().minusMinutes(10));
        KnowledgeConversation claimedView =
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 4, REQUEST_ID, 3L,
                        KnowledgeConversationStatus.ACTIVE);
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(occupied, claimedView);
        KnowledgeTurn occupant = turn(200L, 91L, 3, KnowledgeTurnStatus.PROCESSING);
        occupant.setRequestId(occupantRequestId);
        when(turnMapper.findByRequestId(USER_ID, occupantRequestId)).thenReturn(occupant);
        when(turnMapper.fail(200L, USER_ID, occupantRequestId, "QUESTION_PROCESS_INTERRUPTED")).thenReturn(1);
        when(conversationMapper.releaseExecution(91L, USER_ID, occupantRequestId)).thenReturn(1);
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(1);
        doAnswer(inv -> {
            inv.<KnowledgeTurn>getArgument(0).setId(201L);
            return 1;
        }).when(turnMapper).insert(any(KnowledgeTurn.class));

        KnowledgeQuestionAcceptedResponse result = service.accept(followUp("新问题"));

        assertEquals(4, result.turnNo());
        assertFalse(result.reused());
        // 加锁顺序契约：先收敛轮次、再释放执行权、最后取得执行权（与完成/失败事务一致，避免死锁）。
        InOrder order = org.mockito.Mockito.inOrder(conversationMapper, turnMapper);
        order.verify(turnMapper).fail(200L, USER_ID, occupantRequestId, "QUESTION_PROCESS_INTERRUPTED");
        order.verify(conversationMapper).releaseExecution(91L, USER_ID, occupantRequestId);
        order.verify(conversationMapper).claimExecution(91L, USER_ID, REQUEST_ID);
        // 被收敛轮次的订阅者也要收到终态，否则会一直等到流超时。
        verify(eventPublisher).publishAfterCommit(200L, false);
    }

    @Test
    void followUpFreshOccupantIsNotRecoveredAndStaysBusy() {
        String occupantRequestId = "80d17d21-2222-4222-8222-222222222222";
        KnowledgeConversation occupied =
                conversation(KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, 3, occupantRequestId, 3L,
                        KnowledgeConversationStatus.ACTIVE);
        occupied.setActiveRequestStartedAt(LocalDateTime.now()); // 占用还新鲜：不收敛
        when(conversationMapper.findOwnedById(91L, USER_ID)).thenReturn(occupied, occupied);
        when(conversationMapper.claimExecution(91L, USER_ID, REQUEST_ID)).thenReturn(0);

        assertBusinessError(() -> service.accept(followUp("问题")),
                ErrorCode.CONFLICT, "CONVERSATION_BUSY");
        verify(turnMapper, never()).fail(anyLong(), anyLong(), anyString(), anyString());
        verify(conversationMapper, never()).releaseExecution(anyLong(), anyLong(), anyString());
    }

    // ---- fixtures ----

    private MediaFile readyMedia() {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(USER_ID);
        return media;
    }

    private void assignIdsOnInsert() {
        doAnswer(inv -> {
            inv.<KnowledgeConversation>getArgument(0).setId(100L);
            return 1;
        }).when(conversationMapper).insert(any(KnowledgeConversation.class));
        doAnswer(inv -> {
            inv.<KnowledgeTurn>getArgument(0).setId(200L);
            return 1;
        }).when(turnMapper).insert(any(KnowledgeTurn.class));
    }

    private KnowledgeQuestionCommandService.SubmitCommand firstQuestion(String question) {
        return new KnowledgeQuestionCommandService.SubmitCommand(
                USER_ID, REQUEST_ID, question, KnowledgeScopeType.SINGLE_VIDEO, MEDIA_ID, null);
    }

    private KnowledgeQuestionCommandService.SubmitCommand followUp(String question) {
        return new KnowledgeQuestionCommandService.SubmitCommand(
                USER_ID, REQUEST_ID, question, null, null, 91L);
    }

    private KnowledgeQuestionCommandService.CompletionInput completionInput() {
        return new KnowledgeQuestionCommandService.CompletionInput(
                USER_ID, 314L, REQUEST_ID, AnswerMode.VIDEO_GROUNDED, true, "回答",
                "改写", "HYBRID", 6, 2, 1234L, List.of());
    }

    private KnowledgeConversation conversation(KnowledgeScopeType scopeType, Long scopeMediaId,
                                               int lastTurnNo, String activeRequestId, long version,
                                               KnowledgeConversationStatus status) {
        KnowledgeConversation conversation = new KnowledgeConversation();
        conversation.setId(91L);
        conversation.setUserId(USER_ID);
        conversation.setScopeType(scopeType);
        conversation.setScopeMediaId(scopeMediaId);
        conversation.setTitle("标题");
        conversation.setStatus(status);
        conversation.setVersion(version);
        conversation.setLastTurnNo(lastTurnNo);
        conversation.setActiveRequestId(activeRequestId);
        return conversation;
    }

    private KnowledgeTurn turn(Long id, Long conversationId, int turnNo, KnowledgeTurnStatus status) {
        KnowledgeTurn turn = new KnowledgeTurn();
        turn.setId(id);
        turn.setUserId(USER_ID);
        turn.setConversationId(conversationId);
        turn.setTurnNo(turnNo);
        turn.setRequestId(REQUEST_ID);
        turn.setStatus(status);
        return turn;
    }

    private KnowledgeTurnEvidence evidence(int rank, String snippet) {
        KnowledgeTurnEvidence evidence = new KnowledgeTurnEvidence();
        evidence.setEvidenceRank(rank);
        evidence.setMediaId(MEDIA_ID);
        evidence.setTitleSnapshot("标题快照");
        evidence.setStartMs(1000L);
        evidence.setEndMs(5000L);
        evidence.setSource("ASR");
        evidence.setSnippet(snippet);
        evidence.setScore(0.9d);
        return evidence;
    }

    private BusinessException assertBusinessError(Executable action, ErrorCode expectedHttp,
                                                  String messageContains) {
        BusinessException e = assertThrows(BusinessException.class, action);
        assertEquals(expectedHttp, e.errorCode());
        assertTrue(e.getMessage().contains(messageContains),
                "期望 message 包含 [" + messageContains + "]，实际: " + e.getMessage());
        return e;
    }

    private String messageCode(BusinessException e) {
        int colon = e.getMessage().indexOf('：');
        return colon < 0 ? e.getMessage() : e.getMessage().substring(0, colon);
    }
}
