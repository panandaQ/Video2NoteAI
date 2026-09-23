package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.dto.knowledge.KnowledgeConversationResponse;
import com.example.server.dto.knowledge.KnowledgeErrorCode;
import com.example.server.dto.knowledge.KnowledgeQuestionAcceptedResponse;
import com.example.server.dto.knowledge.KnowledgeQuestionRequest;
import com.example.server.dto.knowledge.KnowledgeTurnResponse;
import com.example.server.dto.knowledge.KnowledgeTurnStatus;
import com.example.server.entity.KnowledgeConversation;
import com.example.server.entity.KnowledgeTurn;
import com.example.server.exception.BusinessException;
import com.example.server.service.AuthService;
import com.example.server.service.TaskEventService;
import com.example.server.service.knowledge.KnowledgeConversationQueryService;
import com.example.server.service.knowledge.KnowledgeConversationLifecycleService;
import com.example.server.service.knowledge.KnowledgeQuestionCommandService;
import com.example.server.service.knowledge.KnowledgeQuestionExecutor;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识问答入口（runbook §5）。
 *
 * <p>分工（§2.6）：Controller 只负责鉴权、参数接收、受理事务和调度；执行器产出结果，
 * 完成/失败短事务落库并发信号。请求线程不检索、不调用模型。
 *
 * <p>调度失败语义（runbook §6.1）：有界执行器拒绝时立即条件更新为
 * {@code FAILED/QUESTION_QUEUE_FULL}、释放执行权并返回 503——绝不留下“受理了却永远没人执行”的轮次。
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeQuestionController {

    private final KnowledgeQuestionCommandService commandService;
    private final KnowledgeConversationQueryService queryService;
    private final KnowledgeQuestionExecutor executor;
    private final KnowledgeConversationLifecycleService lifecycleService;
    private final TaskEventService taskEventService;
    private final Executor aiTaskExecutor;

    public KnowledgeQuestionController(KnowledgeQuestionCommandService commandService,
                                       KnowledgeConversationQueryService queryService,
                                       KnowledgeConversationLifecycleService lifecycleService,
                                       KnowledgeQuestionExecutor executor,
                                       TaskEventService taskEventService,
                                       @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
        this.executor = executor;
        this.taskEventService = taskEventService;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    /**
     * 首问 / 追问（runbook §5.1 / §5.2）：成功受理返回 202；
     * 相同 requestId 幂等回放——仍在处理返回同一 202，已终态返回 200 与原终态。
     */
    @PostMapping("/questions")
    public ResponseEntity<Result<KnowledgeQuestionAcceptedResponse>> ask(
            @Valid @RequestBody KnowledgeQuestionRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        KnowledgeQuestionCommandService.SubmitCommand command =
                new KnowledgeQuestionCommandService.SubmitCommand(
                        userId, request.requestId(), request.question(),
                        request.scope() == null ? null : request.scope().type(),
                        request.scope() == null ? null : request.scope().mediaId(),
                        request.conversationId());
        KnowledgeQuestionAcceptedResponse accepted = commandService.accept(command);

        if (accepted.status() == KnowledgeTurnStatus.PROCESSING && !accepted.reused()) {
            dispatch(userId, accepted);
        }

        KnowledgeQuestionAcceptedResponse body = accepted.withEventsUrl(eventsUrl(accepted));
        if (accepted.reused() && accepted.status().isTerminal()) {
            // 幂等回放：原轮次已完成/已失败时返回 200 与原终态，不自动重新执行（runbook §5.2）。
            return ResponseEntity.ok(Result.ok(body));
        }
        return ResponseEntity.accepted().body(Result.ok(body));
    }

    @GetMapping("/conversations/{conversationId}")
    public Result<KnowledgeConversationResponse> conversation(
            @PathVariable Long conversationId,
            @RequestParam(required = false) Long knownVersion,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(queryService.conversationHeader(userId, conversationId, knownVersion));
    }

    @GetMapping("/conversations")
    public Result<List<KnowledgeConversationResponse>> conversations(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long mediaId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(queryService.conversations(userId, cursor, limit, mediaId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Result<String> deleteConversation(
            @PathVariable Long conversationId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        lifecycleService.deleteOwned(userId, conversationId);
        return Result.ok("会话已删除");
    }

    @GetMapping("/conversations/{conversationId}/turns")
    public Result<List<KnowledgeTurnResponse>> turns(
            @PathVariable Long conversationId,
            @RequestParam(required = false) Integer beforeTurnNo,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long knownVersion,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(queryService.turns(userId, conversationId, beforeTurnNo, limit, knownVersion));
    }

    @GetMapping("/conversations/{conversationId}/turns/{turnId}")
    public Result<KnowledgeTurnResponse> turn(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(queryService.turn(userId, conversationId, turnId));
    }

    /**
     * 单轮终态 SSE（runbook §5.5）：先按 {@code conversationId + turnId + userId} 做归属校验，
     * 再走“先注册订阅者、后回放当前状态”的入口，不能先查状态再注册（否则“注册前完成”的
     * 竞态会永久丢失终态事件）。PROCESSING 回放后保持连接，终态回放或实时终态到达后关闭。
     */
    @GetMapping(value = "/conversations/{conversationId}/turns/{turnId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        queryService.requireOwnedTurn(userId, conversationId, turnId);
        return taskEventService.subscribeKnowledgeQuestion(turnId,
                () -> queryService.currentEvent(userId, conversationId, turnId));
    }

    private void dispatch(Long userId, KnowledgeQuestionAcceptedResponse accepted) {
        // 从 MySQL 取执行器需要的定位信息（媒体、问题、traceId）：受理响应不携带这些字段。
        KnowledgeConversation conversation =
                queryService.requireOwnedConversation(userId, accepted.conversationId());
        KnowledgeTurn turn =
                queryService.requireOwnedTurn(userId, accepted.conversationId(), accepted.turnId());
        try {
            aiTaskExecutor.execute(() -> executor.process(new KnowledgeQuestionExecutor.ProcessCommand(
                    userId, accepted.conversationId(), accepted.turnId(), accepted.requestId(),
                    accepted.conversationVersion(), conversation.getScopeMediaId(),
                    turn.getQuestion(), turn.getTraceId())));
        } catch (RejectedExecutionException e) {
            // 队列满：立即收敛为可重试失败并释放执行权，返回 503（runbook §6.1）。
            commandService.fail(new KnowledgeQuestionCommandService.FailureInput(
                    userId, accepted.turnId(), accepted.requestId(), KnowledgeErrorCode.QUESTION_QUEUE_FULL));
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    KnowledgeErrorCode.QUESTION_QUEUE_FULL.messageWithCode());
        }
    }

    private String eventsUrl(KnowledgeQuestionAcceptedResponse accepted) {
        return "/knowledge/conversations/" + accepted.conversationId()
                + "/turns/" + accepted.turnId() + "/events";
    }
}
