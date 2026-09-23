package com.example.server.service.knowledge;

import com.example.server.common.ErrorCode;
import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.knowledge.HistoryTurn;
import com.example.server.dto.knowledge.KnowledgeConversationResponse;
import com.example.server.dto.knowledge.KnowledgeTurnResponse;
import com.example.server.dto.knowledge.KnowledgeTurnStatus;
import com.example.server.entity.KnowledgeConversation;
import com.example.server.entity.KnowledgeTurn;
import com.example.server.entity.KnowledgeTurnEvidence;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.KnowledgeConversationMapper;
import com.example.server.mapper.KnowledgeTurnEvidenceMapper;
import com.example.server.mapper.KnowledgeTurnMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 问答只读查询（runbook §3.2 / §5.3 / §5.4）：会话头、历史页、单轮结果与 SSE 回放事件；
 * 执行器也用它加载消歧历史。
 *
 * <p>模块二 P2 后，会话头与最近历史使用 Redis-first 有界投影；缓存 miss、损坏、旧版本或故障
 * 完整回源 MySQL。提交、权限、单轮终态与历史分页仍直接读取 MySQL，不存在与越权统一 404。
 */
@Service
public class KnowledgeConversationQueryService {

    static final int DEFAULT_PAGE_LIMIT = 20;
    static final int MAX_PAGE_LIMIT = 50;

    private final KnowledgeConversationMapper conversationMapper;
    private final KnowledgeTurnMapper turnMapper;
    private final KnowledgeTurnEvidenceMapper evidenceMapper;
    private final KnowledgeQuestionProperties properties;
    private final KnowledgeConversationProjectionService projectionService;

    public KnowledgeConversationQueryService(KnowledgeConversationMapper conversationMapper,
                                             KnowledgeTurnMapper turnMapper,
                                             KnowledgeTurnEvidenceMapper evidenceMapper,
                                             KnowledgeQuestionProperties properties,
                                             KnowledgeConversationProjectionService projectionService) {
        this.conversationMapper = conversationMapper;
        this.turnMapper = turnMapper;
        this.evidenceMapper = evidenceMapper;
        this.properties = properties;
        this.projectionService = projectionService;
    }

    /**
     * 追问消歧上下文：最近 {@code maxTurns} 个 COMPLETED 轮次，按时间正序（runbook §6.2 第 2 步）。
     *
     * <p>历史只用于消歧：检索与回答阶段的证据仍来自本轮重新检索（D-086）。
     */
    public List<HistoryTurn> loadHistory(Long conversationId, Long userId) {
        return loadHistory(conversationId, userId, properties.getQuestion().getHistoryMaxTurns());
    }

    public List<HistoryTurn> loadHistory(Long conversationId, Long userId, int maxTurns) {
        List<KnowledgeTurn> turns = turnMapper.findRecentCompleted(conversationId, userId, maxTurns);
        return turns.stream()
                .map(turn -> new HistoryTurn(turn.getTurnNo(), turn.getQuestion(), turn.getAnswer()))
                .toList();
    }

    /**
     * 执行器加载历史时携带受理命令的 MySQL version。只有版本完全一致的热投影才可用于模型上下文；
     * 不一致时回源，避免旧历史参与本轮改写。
     */
    public List<HistoryTurn> loadHistory(Long conversationId, Long userId, long expectedVersion) {
        return projectionService.findExact(userId, conversationId, expectedVersion)
                .map(projection -> {
                    List<KnowledgeTurnResponse> completed = projection.recentTurns().stream()
                            .filter(turn -> turn.status() == KnowledgeTurnStatus.COMPLETED)
                            .toList();
                    int from = Math.max(0,
                            completed.size() - properties.getQuestion().getHistoryMaxTurns());
                    return completed.subList(from, completed.size()).stream()
                            .map(turn -> new HistoryTurn(turn.turnNo(), turn.question(), turn.answer()))
                            .toList();
                })
                .orElseGet(() -> loadHistory(conversationId, userId));
    }

    public KnowledgeConversation requireOwnedConversation(Long userId, Long conversationId) {
        KnowledgeConversation conversation = conversationMapper.findOwnedById(conversationId, userId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    /** 单轮归属：会话与轮次都必须属于当前用户，且轮次确属该会话；不满足统一 404。 */
    public KnowledgeTurn requireOwnedTurn(Long userId, Long conversationId, Long turnId) {
        KnowledgeTurn turn = turnMapper.findOwnedById(turnId, userId);
        if (turn == null || !conversationId.equals(turn.getConversationId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "轮次不存在");
        }
        return turn;
    }

    public KnowledgeConversationResponse conversationHeader(Long userId, Long conversationId) {
        return conversationHeader(userId, conversationId, null);
    }

    public KnowledgeConversationResponse conversationHeader(Long userId, Long conversationId,
                                                            Long knownVersion) {
        return projectionService.findOrLoad(userId, conversationId, knownVersion)
                .map(projection -> projection.conversation())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
    }

    /** 最近会话列表始终以 MySQL 归属与 updated_at 游标为准。 */
    public List<KnowledgeConversationResponse> conversations(Long userId, Long cursor, Integer limit) {
        return conversations(userId, cursor, limit, null);
    }

    /** 可按当前单视频过滤；过滤发生在 MySQL，避免客户端分页后漏掉较早会话。 */
    public List<KnowledgeConversationResponse> conversations(Long userId, Long cursor,
                                                             Integer limit, Long mediaId) {
        int size = clampLimit(limit);
        List<KnowledgeConversation> rows;
        if (mediaId == null) {
            rows = cursor == null
                    ? conversationMapper.findOwnedPage(userId, size)
                    : conversationMapper.findOwnedPageBefore(userId, cursor, size);
        } else {
            rows = cursor == null
                    ? conversationMapper.findOwnedMediaPage(userId, mediaId, size)
                    : conversationMapper.findOwnedMediaPageBefore(userId, mediaId, cursor, size);
        }
        return rows.stream().map(projectionService::toHeader).toList();
    }

    /**
     * 历史页（runbook §5.4）：{@code beforeTurnNo} 为空取最新一页；返回时间正序。
     * {@code limit} 收敛到 [1, 50]，默认 20——分页大小不构成攻击面，静默收敛避免无谓的客户端往返。
     */
    public List<KnowledgeTurnResponse> turns(Long userId, Long conversationId,
                                             Integer beforeTurnNo, Integer limit) {
        return turns(userId, conversationId, beforeTurnNo, limit, null);
    }

    public List<KnowledgeTurnResponse> turns(Long userId, Long conversationId,
                                             Integer beforeTurnNo, Integer limit,
                                             Long knownVersion) {
        int size = clampLimit(limit);
        if (beforeTurnNo == null && size <= properties.getConversation().getHotMaxTurns()) {
            return projectionService.findOrLoad(userId, conversationId, knownVersion)
                    .map(projection -> tail(projection.recentTurns(), size))
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
        }
        requireOwnedConversation(userId, conversationId);
        List<KnowledgeTurn> turns = turnMapper.findPageBefore(conversationId, userId, beforeTurnNo, size);
        List<KnowledgeTurn> ascending = new ArrayList<>(turns);
        Collections.reverse(ascending);
        return composeTurns(ascending);
    }

    public KnowledgeTurnResponse turn(Long userId, Long conversationId, Long turnId) {
        KnowledgeTurn turn = requireOwnedTurn(userId, conversationId, turnId);
        List<KnowledgeTurnEvidence> evidence = evidenceMapper.findByTurnId(turnId);
        return composeTurn(turn, evidence);
    }

    /** SSE 订阅的注册后回放事件：每次调用都重新查库，不复用订阅时的旧快照。 */
    public TaskEvent currentEvent(Long userId, Long conversationId, Long turnId) {
        KnowledgeTurn turn = requireOwnedTurn(userId, conversationId, turnId);
        return TaskEvent.of(project(turn.getStatus()), null);
    }

    private TaskStatus project(KnowledgeTurnStatus status) {
        return switch (status) {
            case COMPLETED -> TaskStatus.of(TaskStatus.State.COMPLETED,
                    KnowledgeQuestionEventPublisher.COMPLETED_MESSAGE);
            case FAILED -> TaskStatus.of(TaskStatus.State.FAILED,
                    KnowledgeQuestionEventPublisher.FAILED_MESSAGE);
            case PROCESSING -> TaskStatus.of(TaskStatus.State.PROCESSING, "正在生成回答");
        };
    }

    private List<KnowledgeTurnResponse> composeTurns(List<KnowledgeTurn> turns) {
        return projectionService.composeTurns(turns);
    }

    private KnowledgeTurnResponse composeTurn(KnowledgeTurn turn, List<KnowledgeTurnEvidence> evidence) {
        return projectionService.composeTurn(turn, evidence);
    }

    private List<KnowledgeTurnResponse> tail(List<KnowledgeTurnResponse> turns, int limit) {
        int from = Math.max(0, turns.size() - limit);
        return List.copyOf(turns.subList(from, turns.size()));
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_PAGE_LIMIT;
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }
}
