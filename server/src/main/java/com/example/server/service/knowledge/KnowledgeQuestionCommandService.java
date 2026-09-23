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
import com.example.server.utils.KnowledgeQuestionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 问答命令的三个短事务：受理、完成、失败（runbook §3.2 / §6）。
 *
 * <p>数据责任划分：
 * <ul>
 *   <li><b>受理</b>：校验 + 幂等回放 + （首问）建会话或（追问）CAS 取执行权 + 插入 PROCESSING 轮次。
 *       事务内只做数据库写入，禁止调用 Redis、Qdrant、Embedding、LLM 或 SSE（runbook §6.1）。</li>
 *   <li><b>完成</b>：只有轮次仍 PROCESSING 且会话执行权仍属于本次请求才允许写入回答与证据，
 *       随后递增版本并释放执行权——三者同事务，任一失败整体回滚（runbook §6.3）。</li>
 *   <li><b>失败</b>：保存受控错误码、递增版本并释放执行权，不保存半成品。</li>
 * </ul>
 *
 * <p>受理为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}：唯一键冲突
 * （并发同 requestId 的首问）必须<b>在事务回滚之后</b>回读赢家轮次并返回幂等结果——注解式事务的
 * 回滚发生在方法退出时，方法内无法再读到自己回滚后的状态；模板式事务的异常在回调外捕获，回滚已经
 * 完成，可以安全回读。
 *
 * <p>顺序保证：追问的 CAS 赢家在同事务内重读会话行取 {@code last_turn_no} 作为本轮 turnNo
 * （行锁保证读到的是自己递增后的值）；轮次插入失败会把 CAS 一起回滚，不留下 turnNo 空洞。
 */
@Service
public class KnowledgeQuestionCommandService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionCommandService.class);
    private static final int TITLE_MAX_LENGTH = 120;

    private final KnowledgeConversationMapper conversationMapper;
    private final KnowledgeTurnMapper turnMapper;
    private final KnowledgeTurnEvidenceMapper evidenceMapper;
    private final MediaFileMapper mediaFileMapper;
    private final KnowledgeQuestionProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final KnowledgeRequestIdCache requestCache;
    private final KnowledgeQuestionEventPublisher eventPublisher;
    private final KnowledgeConversationProjectionService projectionService;

    public KnowledgeQuestionCommandService(KnowledgeConversationMapper conversationMapper,
                                           KnowledgeTurnMapper turnMapper,
                                           KnowledgeTurnEvidenceMapper evidenceMapper,
                                           MediaFileMapper mediaFileMapper,
                                           KnowledgeQuestionProperties properties,
                                           TransactionTemplate transactionTemplate,
                                           KnowledgeRequestIdCache requestCache,
                                           KnowledgeQuestionEventPublisher eventPublisher,
                                           KnowledgeConversationProjectionService projectionService) {
        this.conversationMapper = conversationMapper;
        this.turnMapper = turnMapper;
        this.evidenceMapper = evidenceMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.requestCache = requestCache;
        this.eventPublisher = eventPublisher;
        this.projectionService = projectionService;
    }

    /** 受理命令：首问只带 scope，追问只带 conversationId（scope 可省略，携带则必须一致）。 */
    public record SubmitCommand(Long userId,
                                String requestId,
                                String question,
                                KnowledgeScopeType scopeType,
                                Long scopeMediaId,
                                Long conversationId) {
    }

    /** 完成命令：问答执行器（Q1a）产出的完整结果与证据。 */
    public record CompletionInput(Long userId,
                                  Long turnId,
                                  String requestId,
                                  AnswerMode answerMode,
                                  boolean videoEvidenceFound,
                                  String answer,
                                  String rewrittenQuery,
                                  String retrievalMode,
                                  Integer retrievedCount,
                                  Integer citedCount,
                                  Long durationMs,
                                  List<KnowledgeTurnEvidence> evidence) {
    }

    /** 失败命令：受控错误码；释放执行权后用户可用新 requestId 在原会话重新生成。 */
    public record FailureInput(Long userId, Long turnId, String requestId, KnowledgeErrorCode errorCode) {
    }

    /**
     * 受理提问（runbook §6.1 的事务部分）。
     *
     * <p>幂等快路径：先查 {@code knowledge:question:request:{userId}:{requestId}} 映射，
     * 命中后仍回 MySQL 校验（真正幂等由 {@code uk_knowledge_turn_request} 保证）；
     * Redis 未命中或故障直接查唯一键。事务提交后最佳努力回写映射，写失败只记日志。
     *
     * @throws BusinessException 400 参数非法、404 媒体/会话不存在或越权、409 媒体未就绪 /
     *         范围不一致 / 会话繁忙、422 范围类型暂不支持
     */
    public KnowledgeQuestionAcceptedResponse accept(SubmitCommand cmd) {
        Long userId = cmd.userId();
        String requestId = requireValidRequestId(cmd.requestId());
        String question = normalizeQuestion(cmd.question());

        // 幂等快查（无事务）：命中直接回放，不创建会话、不消耗执行权。
        KnowledgeTurn existing = findIdempotentTurn(userId, requestId);
        if (existing != null) {
            requestCache.remember(userId, requestId, existing.getConversationId(), existing.getId());
            return replay(existing, userId);
        }

        KnowledgeQuestionAcceptedResponse accepted;
        try {
            // 事务内禁止调用 Redis（runbook §6.1）：映射回写放在提交之后。
            accepted = transactionTemplate.execute(status ->
                    cmd.conversationId() == null
                            ? acceptFirstQuestion(userId, requestId, question, cmd)
                            : acceptFollowUp(userId, requestId, question, cmd));
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：事务已整体回滚（含刚插入的会话与 CAS 占用），回读赢家轮次回放。
            KnowledgeTurn winner = turnMapper.findByRequestId(userId, requestId);
            if (winner == null) {
                throw new IllegalStateException(
                        "requestId 唯一键竞争后未取到轮次: userId=" + userId + " requestId=" + requestId, e);
            }
            log.info("knowledge_question_accepted_replayed userId={} turnId={} reason=duplicate_request",
                    userId, winner.getId());
            requestCache.remember(userId, requestId, winner.getConversationId(), winner.getId());
            return replay(winner, userId);
        }
        requestCache.remember(userId, requestId, accepted.conversationId(), accepted.turnId());
        // accept 的事务已经提交；立即刷新，避免 version 尚未递增时旧 lastTurnNo 投影继续命中。
        projectionService.refreshNow(userId, accepted.conversationId());
        return accepted;
    }

    private KnowledgeTurn findIdempotentTurn(Long userId, String requestId) {
        var mapping = requestCache.find(userId, requestId);
        if (mapping.isPresent()) {
            Long turnId = parseTurnId(mapping.get());
            if (turnId != null) {
                KnowledgeTurn byId = turnMapper.findOwnedById(turnId, userId);
                if (byId != null) {
                    return byId;
                }
            }
        }
        return turnMapper.findByRequestId(userId, requestId);
    }

    private Long parseTurnId(String value) {
        try {
            return Long.parseLong(value.substring(value.indexOf(':') + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 完成轮次（runbook §6.3）：回答、证据、版本递增与执行权释放同事务。
     *
     * @return {@code false} 表示轮次已终态或执行权已转移（旧执行线程在僵尸收敛后返回），结果丢弃
     */
    @Transactional
    public boolean complete(CompletionInput input) {
        KnowledgeTurn turn = turnMapper.findOwnedById(input.turnId(), input.userId());
        if (turn == null) {
            log.info("knowledge_turn_complete_discarded turnId={} reason=turn_missing", input.turnId());
            return false;
        }
        int updated = turnMapper.complete(input.turnId(), input.userId(), input.requestId(),
                input.rewrittenQuery(), input.answerMode().name(), input.videoEvidenceFound(),
                input.answer(), input.retrievalMode(), input.retrievedCount(), input.citedCount(),
                input.durationMs());
        if (updated == 0) {
            log.info("knowledge_turn_complete_discarded turnId={} reason=not_processing_or_lease_lost",
                    input.turnId());
            return false;
        }
        List<KnowledgeTurnEvidence> evidence = input.evidence() == null ? List.of() : input.evidence();
        if (!evidence.isEmpty()) {
            for (KnowledgeTurnEvidence row : evidence) {
                row.setTurnId(turn.getId());
            }
            evidenceMapper.insertBatch(turn.getId(), evidence);
        }
        releaseOrFail(turn.getConversationId(), input.userId(), input.requestId(), input.turnId());
        projectionService.refreshAfterCommit(input.userId(), turn.getConversationId());
        // 终态发布收敛在“条件更新成功”这一语义：事务提交后发布，订阅者收到时 MySQL 终态已可见。
        eventPublisher.publishAfterCommit(turn.getId(), true);
        return true;
    }

    /**
     * 失败轮次（runbook §6.3）：受控错误码、版本递增与执行权释放同事务，不保存半成品。
     *
     * @return {@code false} 表示轮次已终态或执行权已转移，结果丢弃
     */
    @Transactional
    public boolean fail(FailureInput input) {
        KnowledgeTurn turn = turnMapper.findOwnedById(input.turnId(), input.userId());
        if (turn == null) {
            log.info("knowledge_turn_fail_discarded turnId={} reason=turn_missing", input.turnId());
            return false;
        }
        int updated = turnMapper.fail(input.turnId(), input.userId(), input.requestId(),
                input.errorCode().code());
        if (updated == 0) {
            log.info("knowledge_turn_fail_discarded turnId={} reason=not_processing_or_lease_lost",
                    input.turnId());
            return false;
        }
        releaseOrFail(turn.getConversationId(), input.userId(), input.requestId(), input.turnId());
        projectionService.refreshAfterCommit(input.userId(), turn.getConversationId());
        eventPublisher.publishAfterCommit(turn.getId(), false);
        return true;
    }

    private KnowledgeQuestionAcceptedResponse acceptFirstQuestion(Long userId, String requestId,
                                                                   String question, SubmitCommand cmd) {
        if (cmd.scopeType() == null) {
            throw invalid("首问必须指定 scope");
        }
        if (cmd.scopeType() == KnowledgeScopeType.LIBRARY) {
            // 当前切片只支持单视频问答（runbook §9 / handoff Q0 边界），LIBRARY 稳定拒绝。
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    KnowledgeErrorCode.KNOWLEDGE_SCOPE_NOT_SUPPORTED.messageWithCode());
        }
        Long mediaId = cmd.scopeMediaId();
        if (mediaId == null) {
            throw invalid("SINGLE_VIDEO 范围必须携带 mediaId");
        }
        requireReadyMedia(mediaId, userId);

        // 首问在 INSERT 里直接携带执行权：新会话行在提交前对其他事务不可见，不存在 CAS 竞争。
        KnowledgeConversation conversation = new KnowledgeConversation();
        conversation.setUserId(userId);
        conversation.setScopeType(KnowledgeScopeType.SINGLE_VIDEO);
        conversation.setScopeMediaId(mediaId);
        conversation.setTitle(truncateTitle(question));
        conversation.setStatus(KnowledgeConversationStatus.ACTIVE);
        conversation.setVersion(0L);
        conversation.setLastTurnNo(1);
        conversation.setActiveRequestId(requestId);
        conversation.setActiveRequestStartedAt(LocalDateTime.now());
        conversationMapper.insert(conversation);

        KnowledgeTurn turn = buildTurn(conversation.getId(), 1, userId, requestId, question, mediaId);
        turnMapper.insert(turn);
        return accepted(conversation, turn, false);
    }

    private KnowledgeQuestionAcceptedResponse acceptFollowUp(Long userId, String requestId,
                                                              String question, SubmitCommand cmd) {
        KnowledgeConversation conversation = conversationMapper.findOwnedById(cmd.conversationId(), userId);
        if (conversation == null) {
            throw notFound("会话不存在");
        }
        requireScopeConsistent(cmd, conversation);

        // 受理懒检查（runbook §6.4）：占用已超判死阈值时先收敛僵尸轮次，再尝试取得执行权。
        // 必须在 claim 之前完成：先轮次后会话的加锁顺序与完成/失败事务、恢复扫描一致，避免死锁。
        recoverStaleOccupant(conversation, userId, requestId);

        int claimed = conversationMapper.claimExecution(conversation.getId(), userId, requestId);
        if (claimed == 0) {
            // CAS 未命中后重读分流：相同 requestId 回放；其他请求活跃则 409；执行权为空则会话不可用。
            KnowledgeConversation current = conversationMapper.findOwnedById(conversation.getId(), userId);
            if (current == null) {
                throw notFound("会话不存在");
            }
            if (requestId.equals(current.getActiveRequestId())) {
                KnowledgeTurn existing = turnMapper.findByRequestId(userId, requestId);
                if (existing == null) {
                    throw new IllegalStateException(
                            "会话执行权指向的轮次不存在: conversationId=" + conversation.getId());
                }
                return replay(existing, userId);
            }
            if (current.getActiveRequestId() != null) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        KnowledgeErrorCode.CONVERSATION_BUSY.messageWithCode());
            }
            throw new BusinessException(ErrorCode.CONFLICT, "会话状态不允许提问");
        }

        // CAS 赢家在同事务内重读：赢家持有行锁直到提交，读到的必然是本次递增后的 last_turn_no，
        // 它就是本轮 turnNo（旧执行线程或并发请求都无法插入中间状态）。
        KnowledgeConversation claimedView = conversationMapper.findOwnedById(conversation.getId(), userId);
        int turnNo = claimedView.getLastTurnNo();
        KnowledgeTurn turn = buildTurn(conversation.getId(), turnNo, userId, requestId, question,
                conversation.getScopeMediaId());
        turnMapper.insert(turn);
        return accepted(claimedView, turn, false);
    }

    /**
     * 受理懒检查：会话执行权占用超过判死阈值且占用轮次仍 PROCESSING 时，就地收敛为
     * {@code FAILED/QUESTION_PROCESS_INTERRUPTED} 并释放执行权。
     *
     * <p>与完成/失败事务、恢复扫描使用同一加锁顺序（先轮次、后会话），且当前处于受理事务内：
     * 条件更新未命中说明竞争者已把它推进到终态，直接放弃，交给随后的 claim 与重读分流。
     */
    private void recoverStaleOccupant(KnowledgeConversation conversation, Long userId, String requestId) {
        String occupantRequestId = conversation.getActiveRequestId();
        if (occupantRequestId == null || occupantRequestId.equals(requestId)
                || conversation.getActiveRequestStartedAt() == null) {
            return;
        }
        LocalDateTime staleBefore = LocalDateTime.now()
                .minusSeconds(properties.getQuestion().getProcessingStaleSeconds());
        if (!conversation.getActiveRequestStartedAt().isBefore(staleBefore)) {
            return;
        }
        KnowledgeTurn occupant = turnMapper.findByRequestId(userId, occupantRequestId);
        if (occupant == null || occupant.getStatus() != KnowledgeTurnStatus.PROCESSING) {
            return;
        }
        int failed = turnMapper.fail(occupant.getId(), userId, occupant.getRequestId(),
                KnowledgeErrorCode.QUESTION_PROCESS_INTERRUPTED.code());
        if (failed == 0) {
            return;
        }
        conversationMapper.releaseExecution(conversation.getId(), userId, occupant.getRequestId());
        // 被收敛轮次的订阅者也要收到终态：否则它们会一直等到流超时。
        eventPublisher.publishAfterCommit(occupant.getId(), false);
        log.info("knowledge_question_lazy_recovered conversationId={} turnId={}",
                conversation.getId(), occupant.getId());
    }

    private void requireReadyMedia(Long mediaId, Long userId) {
        MediaFile ready = mediaFileMapper.findOwnedReadyById(mediaId, userId);
        if (ready != null) {
            return;
        }
        // 区分“媒体未 READY”与“不存在/越权”：统一 404 不得通过状态码泄漏其他用户的媒体存在性。
        if (mediaFileMapper.findOwnedIdById(mediaId, userId) == null) {
            throw notFound("视频不存在");
        }
        throw new BusinessException(ErrorCode.CONFLICT, KnowledgeErrorCode.MEDIA_NOT_READY.messageWithCode());
    }

    private void requireScopeConsistent(SubmitCommand cmd, KnowledgeConversation conversation) {
        if (cmd.scopeType() == null) {
            return;
        }
        if (cmd.scopeType() == KnowledgeScopeType.SINGLE_VIDEO && cmd.scopeMediaId() == null) {
            throw invalid("携带 scope 时必须同时指定 mediaId");
        }
        boolean matches = cmd.scopeType() == conversation.getScopeType()
                && java.util.Objects.equals(cmd.scopeMediaId(), conversation.getScopeMediaId());
        if (!matches) {
            // D-082：会话范围创建后不可变；需要切换范围时创建新会话。
            throw new BusinessException(ErrorCode.CONFLICT,
                    KnowledgeErrorCode.CONVERSATION_SCOPE_MISMATCH.messageWithCode());
        }
    }

    private KnowledgeTurn buildTurn(Long conversationId, int turnNo, Long userId, String requestId,
                                    String question, Long scopeMediaId) {
        KnowledgeTurn turn = new KnowledgeTurn();
        turn.setUserId(userId);
        turn.setConversationId(conversationId);
        turn.setTurnNo(turnNo);
        turn.setRequestId(requestId);
        turn.setTraceId(UUID.randomUUID().toString());
        turn.setQuestion(question);
        turn.setStatus(KnowledgeTurnStatus.PROCESSING);
        turn.setScopeMediaCount(1);
        turn.setScopeFingerprint(KnowledgeQuestionKeys.scopeFingerprint(List.of(scopeMediaId)));
        return turn;
    }

    private KnowledgeQuestionAcceptedResponse accepted(KnowledgeConversation conversation,
                                                       KnowledgeTurn turn, boolean reused) {
        // eventsUrl 由 Controller 按请求上下文拼接（runbook §5.2），服务层返回 null。
        return new KnowledgeQuestionAcceptedResponse(
                conversation.getId(), turn.getId(), turn.getTurnNo(),
                turn.getRequestId(), turn.getStatus(), conversation.getVersion(), reused, null);
    }

    private KnowledgeQuestionAcceptedResponse replay(KnowledgeTurn turn, Long userId) {
        KnowledgeConversation conversation = conversationMapper.findOwnedById(turn.getConversationId(), userId);
        if (conversation == null) {
            throw new IllegalStateException(
                    "轮次所属会话缺失: conversationId=" + turn.getConversationId());
        }
        return accepted(conversation, turn, true);
    }

    /**
     * 释放执行权；0 行是防御性失败——完成/失败的条件更新已经校验执行权，正常路径不可能发生。
     * 一旦发生必须抛异常回滚，绝不能留下“执行权已丢、轮次却已终态”或反向的不一致。
     */
    private void releaseOrFail(Long conversationId, Long userId, String requestId, Long turnId) {
        int released = conversationMapper.releaseExecution(conversationId, userId, requestId);
        if (released == 0) {
            throw new IllegalStateException("执行权释放失败: turnId=" + turnId);
        }
    }

    private String requireValidRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("requestId 不能为空");
        }
        try {
            return UUID.fromString(raw).toString();
        } catch (IllegalArgumentException e) {
            throw invalid("requestId 必须是合法的 UUID");
        }
    }

    private String normalizeQuestion(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw invalid("问题不能为空");
        }
        String question = raw.trim();
        int maxLength = properties.getQuestion().getMaxLength();
        if (question.length() > maxLength) {
            throw invalid("问题长度不能超过 " + maxLength + " 个字符");
        }
        return question;
    }

    private String truncateTitle(String question) {
        return question.length() > TITLE_MAX_LENGTH
                ? question.substring(0, TITLE_MAX_LENGTH)
                : question;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }
}
