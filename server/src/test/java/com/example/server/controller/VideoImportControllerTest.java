package com.example.server.controller;

import com.example.server.common.ErrorCode;
import com.example.server.config.AuthInterceptor;
import com.example.server.config.WebConfig;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.dto.VideoImportSubmissionResponse;
import com.example.server.entity.VideoImportJob;
import com.example.server.exception.BusinessException;
import com.example.server.service.AuthService;
import com.example.server.service.TaskEventService;
import com.example.server.service.ingest.ImportJobRetryService;
import com.example.server.service.ingest.ImportJobService;
import com.example.server.service.ingest.VideoImportQueryService;
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
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导入 HTTP 边界（AC-01 / AC-15 / AC-17）。
 *
 * <p>用 standalone MockMvc + 真实 {@link AuthInterceptor}，覆盖真实 WebConfig 注册的两条路径
 * （{@code /videos/**}、{@code /video-imports/**}）：无 Token 必须 401 且响应体仍是统一结构，
 * 受理必须 202 且不回传内部 {@code traceId}。请求线程不允许触碰平台——服务被替换成 mock，
 * 任何"顺手解析一下 URL"的实现都会在这里暴露。
 */
class VideoImportControllerTest {

    private static final Long USER_ID = 7L;
    private static final String TOKEN = "Bearer " + "a".repeat(43);

    private final ImportJobService importJobService = mock(ImportJobService.class);
    private final VideoImportQueryService queryService = mock(VideoImportQueryService.class);
    private final ImportJobRetryService retryService = mock(ImportJobRetryService.class);
    private final AuthService authService = mock(AuthService.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        AuthInterceptor interceptor = new AuthInterceptor(authService, objectMapper, false);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new VideoImportController(importJobService),
                        new VideoImportQueryController(queryService, retryService, taskEventService))
                .addInterceptors(interceptor)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createImportReturns202WithImportIdAndWithoutTraceId() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(importJobService.submit(eq(USER_ID), any(), any(), any())).thenReturn(submission(11L,
                VideoImportJobStatus.QUEUED, false));

        mockMvc.perform(post("/videos/import")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"url\":\"https://www.bilibili.com/video/BV1xx411c7mD\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.importId").value(11))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.reused").value(false))
                .andExpect(jsonPath("$.data.traceId").doesNotExist());
    }

    /** AC-15：`/videos/**` 无有效 Token 必须 401，且响应体仍是统一结构。 */
    @Test
    void createImportWithoutTokenIsUnauthorized() throws Exception {
        when(authService.resolveUser(any())).thenThrow(new SecurityException("请先登录"));

        mockMvc.perform(post("/videos/import")
                        .contentType(APPLICATION_JSON)
                        .content("{\"url\":\"https://www.bilibili.com/video/BV1xx411c7mD\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.code()))
                .andExpect(jsonPath("$.message").value("请先登录"));

        verify(importJobService, never()).submit(anyLong(), any(), any(), any());
    }

    @Test
    void createImportWithBlankUrlIsRejectedByValidation() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);

        mockMvc.perform(post("/videos/import")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"url\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        verify(importJobService, never()).submit(anyLong(), any(), any(), any());
    }

    /** 超长链接由服务层按配置拒绝，接口层必须把业务异常映射成 400 而不是 500。 */
    @Test
    void createImportPropagatesBusinessRejection() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(importJobService.submit(eq(USER_ID), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_ARGUMENT, "视频链接不能超过 40 个字符"));

        mockMvc.perform(post("/videos/import")
                        .header("Authorization", TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("{\"url\":\"https://www.bilibili.com/video/BV1xx411c7mD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("视频链接不能超过 40 个字符"));
    }

    /** 越权或不存在统一 404，不能通过状态码区分"存在但无权限"。 */
    @Test
    void queryForeignImportIsNotFound() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.detail(USER_ID, 99L)).thenThrow(new NoSuchElementException("导入任务不存在"));

        mockMvc.perform(get("/video-imports/99").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));
    }

    @Test
    void queryWithoutTokenIsUnauthorized() throws Exception {
        when(authService.resolveUser(any())).thenThrow(new SecurityException("请先登录"));

        mockMvc.perform(get("/video-imports/11"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.code()));

        verify(queryService, never()).detail(anyLong(), anyLong());
    }

    @Test
    void retryReturns202() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(retryService.retry(USER_ID, 11L))
                .thenReturn(submission(11L, VideoImportJobStatus.QUEUED, false));

        mockMvc.perform(post("/video-imports/11/retry").header("Authorization", TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.importId").value(11));
    }

    @Test
    void retryOfNonRetryableStateIsConflict() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(retryService.retry(USER_ID, 11L))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "当前状态不可重试"));

        mockMvc.perform(post("/video-imports/11/retry").header("Authorization", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT.code()))
                .andExpect(jsonPath("$.message").value("当前状态不可重试"));
    }

    /** 越权订阅终态必须与"任务不存在"统一 404，且不建立订阅、不读任务状态（D-066）。 */
    @Test
    void subscribeForeignImportIsNotFoundWithoutSubscribing() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        when(queryService.requireOwned(USER_ID, 99L)).thenThrow(new NoSuchElementException("导入任务不存在"));

        mockMvc.perform(get("/video-imports/99/events").header("Authorization", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.code()));

        verify(taskEventService, never()).subscribeVideoImport(anyLong(), any());
    }

    @Test
    void subscribeWithoutTokenIsUnauthorized() throws Exception {
        when(authService.resolveUser(any())).thenThrow(new SecurityException("请先登录"));

        mockMvc.perform(get("/video-imports/11/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.code()));

        verify(taskEventService, never()).subscribeVideoImport(anyLong(), any());
    }

    /** 订阅成功路径：SSE 媒体类型 + 状态由查询服务提供（回放顺序由 TaskEventServiceTest 固定）。 */
    @Test
    void subscribeStreamsTerminalEvents() throws Exception {
        when(authService.resolveUser(TOKEN)).thenReturn(USER_ID);
        // 真实实现总会先回放一次当前状态；这里用同样的方式让响应真正写出报文。
        when(taskEventService.subscribeVideoImport(eq(11L), any())).thenAnswer(invocation -> {
            SseEmitter emitter = new SseEmitter();
            emitter.send(SseEmitter.event().name("task-status").data(TaskEvent.of(
                    TaskStatus.of(TaskStatus.State.PROCESSING, "视频导入处理中"), null)));
            return emitter;
        });
        when(queryService.currentEvent(USER_ID, 11L))
                .thenReturn(TaskEvent.of(TaskStatus.of(TaskStatus.State.PROCESSING, "视频导入处理中"), null));

        MvcResult result = mockMvc.perform(get("/video-imports/11/events")
                        .header("Authorization", TOKEN)
                        .accept(TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_EVENT_STREAM))
                .andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("\"state\":\"PROCESSING\""),
                result.getResponse().getContentAsString());

        ArgumentCaptor<Supplier<TaskEvent>> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(taskEventService).subscribeVideoImport(eq(11L), captor.capture());
        // 回放提供者每次被调用都重新查库：返回新值即证明它没有复用订阅时的旧快照。
        when(queryService.currentEvent(USER_ID, 11L)).thenReturn(null);
        assertNull(captor.getValue().get(), "回放必须每次重新查库，不能复用订阅时的旧值");
        // 订阅前只做归属校验（requireOwned），当前状态由 subscribeVideoImport 内部的回放读取。
        verify(queryService).currentEvent(USER_ID, 11L);
    }

    private VideoImportSubmissionResponse submission(Long importId,
                                                    VideoImportJobStatus status,
                                                    boolean reused) {
        VideoImportJob job = new VideoImportJob();
        job.setId(importId);
        job.setUserId(USER_ID);
        job.setStatus(status);
        job.setTraceId("internal-trace-must-not-leak");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        return VideoImportSubmissionResponse.of(job, reused);
    }
}
