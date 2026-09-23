package com.example.server.service;

import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导入终态 SSE 契约（D-066）。
 *
 * <p>断言的是真实 SSE 报文（经 MockMvc 写出），而不是内部的发送调用：事件必须真的能被客户端读到。
 *
 * <p>重点是注册—完成竞态：<b>先注册订阅者、再读取当前状态</b>。测试用一个"在读取状态的那一刻完成任务"
 * 的提供者固定这一点——若实现把两步顺序写反，这个用例必须失败。
 */
class TaskEventServiceTest {

    private static final Long IMPORT_ID = 42L;
    private static final Long TURN_ID = 314L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final TaskEventService service = new TaskEventService(redisTemplate, new ObjectMapper());
    private final TestSseController controller = new TestSseController(service);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 返回 0 表示 Redis 上没有其他实例的订阅者，走本机投递路径。
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(0L);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void subscribeReplaysCurrentStatusImmediately() throws Exception {
        controller.supplier = () -> event(TaskStatus.State.PROCESSING, "视频导入处理中");

        String body = body(subscribe());

        assertTrue(body.contains("\"state\":\"PROCESSING\""), body);
        assertFalse(body.contains("\"state\":\"COMPLETED\""), body);
    }

    @Test
    void terminalReplayDeliversTerminalImmediately() throws Exception {
        controller.supplier = () -> event(TaskStatus.State.COMPLETED, "视频导入完成");

        String body = body(subscribe());

        assertTrue(body.contains("\"state\":\"COMPLETED\""), body);
        assertTrue(body.contains("event:task-status"), body);
    }

    /**
     * 注册与回放之间的完成竞态：状态提供者先发布终态、再返回一个"过期的"处理中状态。
     *
     * <p>因为订阅者已经注册，终态事件必须已经被投递；客户端不会永久等待一个不会再发的终态。
     */
    @Test
    void completionBetweenRegistrationAndReplayIsNotLost() throws Exception {
        controller.supplier = () -> {
            service.publishVideoImport(IMPORT_ID,
                    TaskStatus.of(TaskStatus.State.COMPLETED, "视频导入完成"));
            return event(TaskStatus.State.PROCESSING, "视频导入处理中");
        };

        String body = body(subscribe());

        assertTrue(body.contains("\"state\":\"COMPLETED\""), body);
    }

    /** 回放读库失败不能关闭连接：注册已经生效，后续真正的终态仍然会送达。 */
    @Test
    void replayFailureKeepsStreamOpenForLaterTerminalEvent() throws Exception {
        controller.supplier = () -> {
            throw new IllegalStateException("db down");
        };

        MvcResult result = subscribe();
        assertFalse(body(result).contains("\"state\""), body(result));

        service.publishVideoImport(IMPORT_ID, TaskStatus.of(TaskStatus.State.FAILED, "视频导入失败"));

        assertTrue(body(result).contains("\"state\":\"FAILED\""), body(result));
    }

    /** 终态事件送达后订阅者必须移除：再发一次不能重复投递给同一个连接。 */
    @Test
    void terminalPublishRemovesSubscriber() throws Exception {
        controller.supplier = () -> event(TaskStatus.State.PROCESSING, "视频导入处理中");

        MvcResult result = subscribe();
        service.publishVideoImport(IMPORT_ID, TaskStatus.of(TaskStatus.State.COMPLETED, "视频导入完成"));
        service.publishVideoImport(IMPORT_ID, TaskStatus.of(TaskStatus.State.FAILED, "视频导入失败"));

        String body = body(result);
        assertTrue(body.contains("\"state\":\"COMPLETED\""), body);
        assertFalse(body.contains("\"state\":\"FAILED\""), body);
    }

    /** Redis 发布失败必须降级为本机投递：单实例部署时 Redis 抖动不能让通知整体失效。 */
    @Test
    void redisPublishFailureFallsBackToLocalDelivery() throws Exception {
        controller.supplier = () -> event(TaskStatus.State.PROCESSING, "视频导入处理中");
        MvcResult result = subscribe();
        when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis down"));

        service.publishVideoImport(IMPORT_ID, TaskStatus.of(TaskStatus.State.COMPLETED, "视频导入完成"));

        assertTrue(body(result).contains("\"state\":\"COMPLETED\""), body(result));
    }

    // ---- 知识问答轮次事件（模块二 Q1b）：与导入终态同一套注册-回放语义 ----

    @Test
    void knowledgeQuestionTerminalReplayDeliversAndCloses() throws Exception {
        controller.supplier = () -> event(TaskStatus.State.COMPLETED, "问答完成");

        String body = body(subscribeKnowledgeTurn());

        assertTrue(body.contains("\"state\":\"COMPLETED\""), body);
        assertTrue(body.contains("event:task-status"), body);
    }

    @Test
    void knowledgeQuestionRealTimeTerminalArrivesAfterProcessingReplay() throws Exception {
        controller.supplier = () -> event(TaskStatus.State.PROCESSING, "正在生成回答");

        MvcResult result = subscribeKnowledgeTurn();
        service.publishKnowledgeQuestion(TURN_ID, TaskStatus.of(TaskStatus.State.FAILED, "回答失败，可重新提问"));

        String body = body(result);
        assertTrue(body.contains("\"state\":\"PROCESSING\""), body);
        assertTrue(body.contains("\"state\":\"FAILED\""), body);
    }

    private MvcResult subscribeKnowledgeTurn() throws Exception {
        return mockMvc.perform(get("/test/knowledge-events").param("id", String.valueOf(TURN_ID)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult subscribe() throws Exception {
        return mockMvc.perform(get("/test/import-events").param("id", String.valueOf(IMPORT_ID)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static TaskEvent event(TaskStatus.State state, String message) {
        return TaskEvent.of(TaskStatus.of(state, message), null);
    }

    /** 只为测试存在的最小 SSE 端点：控制器本身在 {@code VideoImportControllerTest} 里验证。 */
    @RestController
    private static final class TestSseController {

        private final TaskEventService taskEventService;
        private volatile Supplier<TaskEvent> supplier;

        private TestSseController(TaskEventService taskEventService) {
            this.taskEventService = taskEventService;
        }

        @GetMapping(value = "/test/import-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter events(@RequestParam Long id) {
            return taskEventService.subscribeVideoImport(id, supplier);
        }

        @GetMapping(value = "/test/knowledge-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter knowledgeEvents(@RequestParam Long id) {
            return taskEventService.subscribeKnowledgeQuestion(id, supplier);
        }
    }
}
