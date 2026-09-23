package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.config.AuthInterceptor;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.knowledge.KnowledgeConversationResponse;
import com.example.server.dto.knowledge.KnowledgeConversationStatus;
import com.example.server.dto.knowledge.KnowledgeQuestionAcceptedResponse;
import com.example.server.dto.knowledge.KnowledgeScopeType;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识问答 HTTP 边界（runbook §5 / §10）：
 * 202 受理与幂等回放（200/202 分流）、409/422/404/400/503 映射、
 * 越权与不存在统一 404、SSE 归属校验与回放、无 Token 401。
 */
class KnowledgeQuestionControllerTest {

    private static final Long USER_ID = 7L;
    private static final String TOKEN = "Bearer " + "a".repeat(43);
    private static final String REQUEST_ID = "b19d07b8-0a36-4fbd-a22c-a1bc0df2018c";

    private final KnowledgeQuestionCommandService commandService = mock(KnowledgeQuestionCommandService.class);
    private final KnowledgeConversationQueryService queryService = mock(KnowledgeConversationQueryService.class);
    private final KnowledgeConversationLifecycleService lifecycleService =
            mock(KnowledgeConversationLifecycleService.class);
    private final KnowledgeQuestionExecutor executor = mock(KnowledgeQuestionExecutor.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final AuthService authService = mock(AuthService.class);
    private final Executor aiTaskExecutor = mock(Executor.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        AuthInterceptor interceptor = new AuthInterceptor(authService, objectMapper, false);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new KnowledgeQuestionController(
                        commandService, queryService, lifecycleService,
                        executor, taskEventService, aiTaskExecutor))
                .addInterceptors(interceptor)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // ---- 受理 ----

    @Test
    void askWithoutTokenIsUnauthorized() throws Exception {
        when(authService.resolveUser(any())).thenThrow(new SecurityException("请先登录"));

        mockMvc.perform(post("/knowledge/questions")
                        .contentType(APPLICATION_JSON)
                        .content(firstQuestionBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.code()));

        verify(commandService, never()).accept(any());
    }

    @Test
    void firstQuestionReturns202WithEventsUrlAndDispatches() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any())).thenReturn(
                accepted(91L, 314L, 4, KnowledgeTurnStatus.PROCESSING, false));
        when(queryService.requireOwnedConversation(USER_ID, 91L)).thenReturn(conversation());
        when(queryService.requireOwnedTurn(USER_ID, 91L, 314L)).thenReturn(turn("问题"));

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(firstQuestionBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.conversationId").value(91))
                .andExpect(jsonPath("$.data.turnId").value(314))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.eventsUrl").value("/knowledge/conversations/91/turns/314/events"));

        verify(aiTaskExecutor).execute(any(Runnable.class));
    }

    @Test
    void followUpBodyIsAcceptedWithoutScope() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any())).thenReturn(
                accepted(91L, 315L, 5, KnowledgeTurnStatus.PROCESSING, false));
        when(queryService.requireOwnedConversation(USER_ID, 91L)).thenReturn(conversation());
        when(queryService.requireOwnedTurn(USER_ID, 91L, 315L)).thenReturn(turn("追问"));

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"requestId\":\"" + REQUEST_ID + "\",\"conversationId\":91,"
                                + "\"question\":\"第二种方案有什么代价？\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<KnowledgeQuestionCommandService.SubmitCommand> command =
                ArgumentCaptor.forClass(KnowledgeQuestionCommandService.SubmitCommand.class);
        verify(commandService).accept(command.capture());
        assertEquals(91L, command.getValue().conversationId());
        assertEquals(null, command.getValue().scopeType());
    }

    @Test
    void idempotentReplayStillProcessingReturns202WithoutDispatch() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any())).thenReturn(
                accepted(91L, 314L, 4, KnowledgeTurnStatus.PROCESSING, true));

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(firstQuestionBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.reused").value(true));

        verify(aiTaskExecutor, never()).execute(any());
    }

    @Test
    void idempotentReplayCompletedReturns200() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any())).thenReturn(
                accepted(91L, 314L, 4, KnowledgeTurnStatus.COMPLETED, true));

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(firstQuestionBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(aiTaskExecutor, never()).execute(any());
    }

    @Test
    void idempotentReplayFailedReturns200() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any())).thenReturn(
                accepted(91L, 314L, 4, KnowledgeTurnStatus.FAILED, true));

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(firstQuestionBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    void askConflictsMapTo409() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any()))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "CONVERSATION_SCOPE_MISMATCH：会话的视频范围不能改变，请创建新会话"));

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(firstQuestionBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT.code()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith("CONVERSATION_SCOPE_MISMATCH")));
    }

    @Test
    void askNotFoundMapsTo404() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(firstQuestionBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
    }

    @Test
    void blankQuestionIsRejectedByValidation() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"requestId\":\"" + REQUEST_ID + "\",\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        verify(commandService, never()).accept(any());
    }

    @Test
    void invalidRequestIdIsRejectedByService() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_ARGUMENT, "requestId 必须是合法的 UUID"));

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"requestId\":\"not-a-uuid\",\"question\":\"问题\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("requestId 必须是合法的 UUID"));
    }

    @Test
    void queueFullFailsTurnAndReturns503() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(commandService.accept(any())).thenReturn(
                accepted(91L, 314L, 4, KnowledgeTurnStatus.PROCESSING, false));
        when(queryService.requireOwnedConversation(USER_ID, 91L)).thenReturn(conversation());
        when(queryService.requireOwnedTurn(USER_ID, 91L, 314L)).thenReturn(turn("问题"));
        doThrow(new RejectedExecutionException("queue full")).when(aiTaskExecutor).execute(any());

        mockMvc.perform(post("/knowledge/questions")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(firstQuestionBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith("QUESTION_QUEUE_FULL")));

        ArgumentCaptor<KnowledgeQuestionCommandService.FailureInput> failure =
                ArgumentCaptor.forClass(KnowledgeQuestionCommandService.FailureInput.class);
        verify(commandService).fail(failure.capture());
        assertEquals("QUESTION_QUEUE_FULL", failure.getValue().errorCode().code());
    }

    // ---- 查询 ----

    @Test
    void conversationHeaderReturnsScopeAndVersion() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.conversationHeader(USER_ID, 91L, null)).thenReturn(new KnowledgeConversationResponse(
                91L, KnowledgeScopeType.SINGLE_VIDEO, 27L, "标题",
                KnowledgeConversationStatus.ACTIVE, 3L, 4,
                LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(get("/knowledge/conversations/91").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(91))
                .andExpect(jsonPath("$.data.scopeType").value("SINGLE_VIDEO"))
                .andExpect(jsonPath("$.data.scopeMediaId").value(27))
                .andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    void conversationHeaderPassesKnownVersion() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.conversationHeader(USER_ID, 91L, 7L)).thenReturn(new KnowledgeConversationResponse(
                91L, KnowledgeScopeType.SINGLE_VIDEO, 27L, "标题",
                KnowledgeConversationStatus.ACTIVE, 7L, 8,
                LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(get("/knowledge/conversations/91")
                        .param("knownVersion", "7")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(7));

        verify(queryService).conversationHeader(USER_ID, 91L, 7L);
    }

    @Test
    void conversationListPassesCursorAndLimit() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.conversations(USER_ID, 90L, 20, 27L)).thenReturn(List.of());

        mockMvc.perform(get("/knowledge/conversations")
                        .param("cursor", "90")
                        .param("limit", "20")
                        .param("mediaId", "27")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk());

        verify(queryService).conversations(USER_ID, 90L, 20, 27L);
    }

    @Test
    void deleteConversationUsesAuthenticatedOwner() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);

        mockMvc.perform(delete("/knowledge/conversations/91")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("会话已删除"));

        verify(lifecycleService).deleteOwned(USER_ID, 91L);
    }

    @Test
    void foreignConversationIs404() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.conversationHeader(USER_ID, 99L, null))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        mockMvc.perform(get("/knowledge/conversations/99").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void turnsPagePassesCursorAndLimit() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.turns(USER_ID, 91L, 20, 10, null)).thenReturn(List.of());

        mockMvc.perform(get("/knowledge/conversations/91/turns")
                        .param("beforeTurnNo", "20").param("limit", "10")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk());

        verify(queryService).turns(USER_ID, 91L, 20, 10, null);
    }

    @Test
    void latestTurnsPassKnownVersion() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.turns(USER_ID, 91L, null, 10, 7L)).thenReturn(List.of());

        mockMvc.perform(get("/knowledge/conversations/91/turns")
                        .param("limit", "10")
                        .param("knownVersion", "7")
                        .header("Authorization", TOKEN))
                .andExpect(status().isOk());

        verify(queryService).turns(USER_ID, 91L, null, 10, 7L);
    }

    @Test
    void singleTurnReturnsResult() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.turn(USER_ID, 91L, 314L)).thenReturn(new KnowledgeTurnResponse(
                314L, 4, REQUEST_ID, "问题", "改写", KnowledgeTurnStatus.COMPLETED,
                "VIDEO_GROUNDED", "回答", null, List.of(), LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(get("/knowledge/conversations/91/turns/314").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turnId").value(314))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.answer").value("回答"));
    }

    @Test
    void foreignTurnIs404WithoutSubscribing() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.requireOwnedTurn(USER_ID, 99L, 999L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "轮次不存在"));

        mockMvc.perform(get("/knowledge/conversations/99/turns/999/events").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());

        verify(taskEventService, never()).subscribeKnowledgeQuestion(anyLong(), any());
    }

    @Test
    void eventsWithoutTokenIs401() throws Exception {
        when(authService.resolveUser(any())).thenThrow(new SecurityException("请先登录"));

        mockMvc.perform(get("/knowledge/conversations/91/turns/314/events"))
                .andExpect(status().isUnauthorized());

        verify(taskEventService, never()).subscribeKnowledgeQuestion(anyLong(), any());
    }

    @Test
    void eventsStreamsReplayState() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(taskEventService.subscribeKnowledgeQuestion(eq(314L), any())).thenAnswer(invocation -> {
            SseEmitter emitter = new SseEmitter();
            emitter.send(SseEmitter.event().name("task-status").data(TaskEvent.of(
                    TaskStatus.of(TaskStatus.State.PROCESSING, "正在生成回答"), null)));
            return emitter;
        });

        MvcResult result = mockMvc.perform(get("/knowledge/conversations/91/turns/314/events")
                        .header("Authorization", TOKEN)
                        .accept(TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_EVENT_STREAM))
                .andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("\"state\":\"PROCESSING\""),
                result.getResponse().getContentAsString());

        ArgumentCaptor<Supplier<TaskEvent>> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(taskEventService).subscribeKnowledgeQuestion(eq(314L), captor.capture());
        verify(queryService).requireOwnedTurn(USER_ID, 91L, 314L);
    }

    // ---- fixtures ----

    private String firstQuestionBody() {
        return "{\"requestId\":\"" + REQUEST_ID + "\","
                + "\"question\":\"视频中如何避免缓存击穿？\","
                + "\"scope\":{\"type\":\"SINGLE_VIDEO\",\"mediaId\":27}}";
    }

    private KnowledgeQuestionAcceptedResponse accepted(Long conversationId, Long turnId, int turnNo,
                                                       KnowledgeTurnStatus status, boolean reused) {
        return new KnowledgeQuestionAcceptedResponse(
                conversationId, turnId, turnNo, REQUEST_ID, status, 3L, reused, null);
    }

    private KnowledgeConversation conversation() {
        KnowledgeConversation conversation = new KnowledgeConversation();
        conversation.setId(91L);
        conversation.setUserId(USER_ID);
        conversation.setScopeType(KnowledgeScopeType.SINGLE_VIDEO);
        conversation.setScopeMediaId(27L);
        return conversation;
    }

    private KnowledgeTurn turn(String question) {
        KnowledgeTurn turn = new KnowledgeTurn();
        turn.setId(314L);
        turn.setUserId(USER_ID);
        turn.setConversationId(91L);
        turn.setTurnNo(4);
        turn.setRequestId(REQUEST_ID);
        turn.setQuestion(question);
        turn.setTraceId("internal-trace");
        return turn;
    }
}
