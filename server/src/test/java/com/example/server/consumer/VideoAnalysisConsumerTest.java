package com.example.server.consumer;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AiService;
import com.example.server.service.AnalysisLifecycleListener;
import com.example.server.service.FailedAnalysisTaskService;
import com.example.server.service.MediaService;
import com.example.server.service.TaskEventService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 契约测试：毒消息收敛与「有限重试 → 死信」的投递次数边界。
 *
 * <p>新增的 Resolver / Acquire Consumer 会沿用同一套模式（结构性非法消息落台账并转死信、
 * 可重试异常交 RocketMQ 重投、投递次数用尽才进死信、台账与死信双失效时拒绝 ACK）。
 * 在复制这套模式之前，先用测试把现有行为固定成可回归的契约。
 */
class VideoAnalysisConsumerTest {

    private static final String DEAD_TOPIC = "video-analysis-dead-topic";
    private static final String HASH = "d41d8cd98f00b204e9800998ecf8427e";
    private static final String GOAL = "总结这个视频";
    private static final Long MEDIA_ID = 1L;
    /** 与 VideoAnalysisConsumer.MAX_DELIVERY_ATTEMPTS 对齐的应用级投递上限。 */
    private static final long MAX_DELIVERY_ATTEMPTS = 3;

    private final AiService aiService = mock(AiService.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final FailedAnalysisTaskService failedTaskService = mock(FailedAnalysisTaskService.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final AnalysisLifecycleListener lifecycleListener = mock(AnalysisLifecycleListener.class);
    private final RLock lock = mock(RLock.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private VideoAnalysisConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new VideoAnalysisConsumer(aiService, redissonClient, redisTemplate,
                checkpointService, rocketMQTemplate, failedTaskService, mediaService,
                taskEventService, List.of(lifecycleListener), DEAD_TOPIC);
    }

    @Test
    void poisonMessageIsRecordedThenDeadLetteredAndAcked() {
        AnalysisTaskMsg msg = new AnalysisTaskMsg(MEDIA_ID, "UNKNOWN_ACTION", HASH, GOAL);

        assertDoesNotThrow(() -> consumer.onMessage(msg));

        verify(failedTaskService).record(eq(msg), eq(0L), any(IllegalArgumentException.class));
        verify(rocketMQTemplate).convertAndSend(DEAD_TOPIC, msg);
        verify(redisTemplate).delete(anyList());
        verify(aiService, never()).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));
    }

    @Test
    void poisonMessageWithMissingGoalIsStillRecorded() {
        AnalysisTaskMsg msg = new AnalysisTaskMsg(MEDIA_ID, AnalysisTaskMsg.START_ANALYSIS, HASH, null);

        assertDoesNotThrow(() -> consumer.onMessage(msg));

        verify(failedTaskService).record(eq(msg), eq(0L), any(IllegalArgumentException.class));
        verify(rocketMQTemplate).convertAndSend(DEAD_TOPIC, msg);
        // 算不出任务身份时不能乱清键，否则会误删其他目标的幂等键。
        verify(redisTemplate, never()).delete(anyList());
    }

    @Test
    void refusesAckWhenLedgerAndDeadLetterBothFail() {
        AnalysisTaskMsg msg = new AnalysisTaskMsg(MEDIA_ID, "UNKNOWN_ACTION", HASH, GOAL);
        doThrow(new IllegalStateException("db down"))
                .when(failedTaskService).record(any(), anyLong(), any());
        doThrow(new IllegalStateException("mq down"))
                .when(rocketMQTemplate).convertAndSend(eq(DEAD_TOPIC), any(AnalysisTaskMsg.class));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(msg));
    }

    @Test
    void acksWhenOnlyLedgerWriteFails() {
        AnalysisTaskMsg msg = new AnalysisTaskMsg(MEDIA_ID, "UNKNOWN_ACTION", HASH, GOAL);
        doThrow(new IllegalStateException("db down"))
                .when(failedTaskService).record(any(), anyLong(), any());

        assertDoesNotThrow(() -> consumer.onMessage(msg));

        verify(rocketMQTemplate).convertAndSend(DEAD_TOPIC, msg);
    }

    @Test
    void retryableFailureBelowLimitIsRethrownForRocketMq() {
        AnalysisTaskMsg msg = startMessage();
        stubAcquiredLock();
        stubDeliveryAttempt(1L);
        when(mediaService.exists(MEDIA_ID)).thenReturn(true);
        doThrow(new IllegalStateException("asr down"))
                .when(aiService).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(msg));

        assertTrue(thrown.getMessage().contains("交由 RocketMQ 重试"), thrown.getMessage());
        verify(failedTaskService, never()).record(any(), anyLong(), any());
        verify(rocketMQTemplate, never()).convertAndSend(eq(DEAD_TOPIC), any(AnalysisTaskMsg.class));
        verify(lifecycleListener).onRetryableFailure(MEDIA_ID, GOAL, AnalysisMode.GENERAL);
        verify(lock).unlock();
    }

    @Test
    void permanentFailureSkipsRetryAndGoesStraightToDeadLetter() {
        AnalysisTaskMsg msg = startMessage();
        stubAcquiredLock();
        stubDeliveryAttempt(1L);
        when(mediaService.exists(MEDIA_ID)).thenReturn(true);
        doThrow(new IllegalArgumentException("media 参数非法"))
                .when(aiService).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));

        assertDoesNotThrow(() -> consumer.onMessage(msg));

        verify(failedTaskService).record(eq(msg), eq(1L), any(IllegalArgumentException.class));
        verify(rocketMQTemplate).convertAndSend(DEAD_TOPIC, msg);
        verify(lifecycleListener).onPermanentFailure(MEDIA_ID, GOAL, AnalysisMode.GENERAL);
    }

    @Test
    void exhaustedDeliveryAttemptsGoToDeadLetter() {
        AnalysisTaskMsg msg = startMessage();
        stubAcquiredLock();
        stubDeliveryAttempt(MAX_DELIVERY_ATTEMPTS);
        when(mediaService.exists(MEDIA_ID)).thenReturn(true);
        doThrow(new IllegalStateException("minio down"))
                .when(aiService).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));

        assertDoesNotThrow(() -> consumer.onMessage(msg));

        verify(failedTaskService).record(eq(msg), eq(MAX_DELIVERY_ATTEMPTS), any(IllegalStateException.class));
        verify(rocketMQTemplate).convertAndSend(DEAD_TOPIC, msg);
        verify(lifecycleListener).onPermanentFailure(MEDIA_ID, GOAL, AnalysisMode.GENERAL);
    }

    @Test
    void lifecycleListenerFailureDoesNotBreakTheAnalysisChain() {
        AnalysisTaskMsg msg = startMessage();
        stubAcquiredLock();
        stubDeliveryAttempt(1L);
        when(mediaService.exists(MEDIA_ID)).thenReturn(true);
        doThrow(new IllegalStateException("aggregate failed"))
                .when(lifecycleListener).onPermanentFailure(anyLong(), anyString(), any(AnalysisMode.class));
        doThrow(new IllegalArgumentException("media 参数非法"))
                .when(aiService).asyncAnalyze(anyLong(), anyString(), any(AnalysisMode.class));

        assertDoesNotThrow(() -> consumer.onMessage(msg));

        verify(failedTaskService).record(eq(msg), eq(1L), any(IllegalArgumentException.class));
        verify(rocketMQTemplate).convertAndSend(DEAD_TOPIC, msg);
    }

    private AnalysisTaskMsg startMessage() {
        return new AnalysisTaskMsg(MEDIA_ID, AnalysisTaskMsg.START_ANALYSIS, HASH, GOAL,
                AnalysisMode.GENERAL.name());
    }

    private void stubAcquiredLock() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private void stubDeliveryAttempt(Long attempt) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(attempt);
    }
}
