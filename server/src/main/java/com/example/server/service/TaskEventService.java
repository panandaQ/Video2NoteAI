package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.utils.AnalysisTaskKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** 后端阶段变化发布到这里，SSE 订阅者只关心事件，不反向依赖业务服务。 */
@Service
public class TaskEventService implements MessageListener {

    public static final String ANALYSIS = "analysis";
    public static final String TRANSCRIPTION = "transcription";
    /** 导入任务终态事件类型（D-066）：Key 形如 {@code video-import:{importId}:default}。 */
    public static final String VIDEO_IMPORT = "video-import";
    /** 知识问答轮次事件类型（模块二 Q1b）：Key 形如 {@code knowledge-question:{turnId}:default}。 */
    public static final String KNOWLEDGE_QUESTION = "knowledge-question";
    public static final String REDIS_CHANNEL = "dovideo:task-events";

    private static final Logger log = LoggerFactory.getLogger(TaskEventService.class);
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers =
            new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TaskEventService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(Long mediaId,
                                String type,
                                String goal,
                                TaskStatus initialStatus,
                                TaskStage stage) {
        return subscribe(mediaId, type, goal, AnalysisMode.GENERAL, initialStatus, stage);
    }

    public SseEmitter subscribe(Long mediaId,
                                String type,
                                String goal,
                                AnalysisMode mode,
                                TaskStatus initialStatus,
                                TaskStage stage) {
        String key = key(mediaId, type, goal, mode);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        send(key, emitter, TaskEvent.of(initialStatus, stage));
        return emitter;
    }

    public void publishAnalysis(Long mediaId, String goal, TaskStatus status, TaskStage stage) {
        publishAnalysis(mediaId, goal, AnalysisMode.GENERAL, status, stage);
    }

    public void publishAnalysis(Long mediaId,
                                String goal,
                                AnalysisMode mode,
                                TaskStatus status,
                                TaskStage stage) {
        publish(key(mediaId, ANALYSIS, goal, mode), TaskEvent.of(status, stage));
    }

    public void publishTranscription(Long mediaId, TaskStatus status, TaskStage stage) {
        publish(key(mediaId, TRANSCRIPTION, "", AnalysisMode.GENERAL), TaskEvent.of(status, stage));
    }

    /** 导入任务终态事件（D-066）；{@code importId} 复用统一的 id 位。 */
    public void publishVideoImport(Long importId, TaskStatus status) {
        publish(key(importId, VIDEO_IMPORT, "", AnalysisMode.GENERAL), TaskEvent.of(status, null));
    }

    /** 知识问答轮次事件（模块二 Q1b）；{@code turnId} 复用统一的 id 位。 */
    public void publishKnowledgeQuestion(Long turnId, TaskStatus status) {
        publish(key(turnId, KNOWLEDGE_QUESTION, "", AnalysisMode.GENERAL), TaskEvent.of(status, null));
    }

    /**
     * 订阅导入任务终态：<b>先注册订阅者，再读取当前状态</b>（D-066 / 计划 §6.3）。
     *
     * <p>顺序不能反过来。若先查状态再注册，任务可能刚好在两步之间完成：事件发布时还没有订阅者，
     * 随后连接拿到的是完成之前读到的 {@code PROCESSING}，客户端就会永久等待一个永远不会再发的终态。
     *
     * <p>{@code currentState} 在注册完成后才被调用；它抛异常时不关闭连接——注册已经生效，
     * 后续真正的终态事件仍然会送达（SSE 是最佳努力，数据库状态才是真源）。
     */
    public SseEmitter subscribeVideoImport(Long importId, Supplier<TaskEvent> currentState) {
        return subscribeReplay(key(importId, VIDEO_IMPORT, "", AnalysisMode.GENERAL), currentState);
    }

    /**
     * 订阅知识问答轮次终态（runbook §5.5）：注册后回放当前状态；{@code PROCESSING} 回放后保持连接，
     * 终态回放或实时终态到达后关闭。语义与导入终态订阅完全一致，只换事件类型。
     */
    public SseEmitter subscribeKnowledgeQuestion(Long turnId, Supplier<TaskEvent> currentState) {
        return subscribeReplay(key(turnId, KNOWLEDGE_QUESTION, "", AnalysisMode.GENERAL), currentState);
    }

    private SseEmitter subscribeReplay(String key, Supplier<TaskEvent> currentState) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        replay(key, emitter, currentState);
        return emitter;
    }

    private void replay(String key, SseEmitter emitter, Supplier<TaskEvent> currentState) {
        TaskEvent event;
        try {
            event = currentState == null ? null : currentState.get();
        } catch (RuntimeException e) {
            log.warn("task_event_replay_failed key={}", key, e);
            return;
        }
        if (event != null) {
            send(key, emitter, event);
        }
    }

    private void publish(String key, TaskEvent event) {
        try {
            String payload = objectMapper.createObjectNode()
                    .put("key", key)
                    .set("event", objectMapper.valueToTree(event))
                    .toString();
            Long receivers = redisTemplate.convertAndSend(REDIS_CHANNEL, payload);
            if (receivers == null || receivers == 0) publishLocal(key, event);
        } catch (RuntimeException e) {
            log.warn("task_event_redis_publish_failed key={}", key, e);
            publishLocal(key, event);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode payload = objectMapper.readTree(message.getBody());
            publishLocal(
                    payload.path("key").asText(),
                    objectMapper.treeToValue(payload.path("event"), TaskEvent.class));
        } catch (Exception e) {
            log.warn("task_event_redis_message_invalid", e);
        }
    }

    private void publishLocal(String key, TaskEvent event) {
        List<SseEmitter> emitters = subscribers.get(key);
        if (emitters == null) return;
        emitters.forEach(emitter -> send(key, emitter, event));
    }

    private void send(String key, SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event().name("task-status").data(event));
            if (event.terminal()) {
                remove(key, emitter);
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            remove(key, emitter);
            emitter.completeWithError(e);
            log.debug("task_event_stream_closed key={}", key);
        }
    }

    private void remove(String key, SseEmitter emitter) {
        subscribers.computeIfPresent(key, (ignored, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private String key(Long mediaId, String type, String goal, AnalysisMode mode) {
        String suffix = ANALYSIS.equals(type)
                ? AnalysisTaskKeys.goalDigest(goal, mode)
                : "default";
        return type + ":" + mediaId + ":" + suffix;
    }
}
