package com.example.server.service;

import com.example.server.config.VideoNoteProperties;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 契约测试：默认目标分析的投递语义。
 *
 * <p>模块一在媒体 `MEDIA_READY` 之后通过 {@code VideoNoteTaskPort} 复用本服务投递固定版本的默认笔记目标。
 * 自动笔记不新增分析消息或 Consumer，所以这里先把现有投递契约固定下来：任务身份键由
 * (contentHash, goalDigest) 决定、重复提交不重复投递、配额拒绝与投递失败都必须释放活跃键。
 */
class AnalysisDispatchServiceTest {

    private static final String TOPIC = "video-analysis-topic";
    /** 默认视频笔记的目标文本；S1 会用集中定义的 `VIDEO_NOTE_V1` 常量替换此处字面量。 */
    private static final String DEFAULT_NOTE_GOAL = "生成默认视频笔记";
    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    private static final Long MEDIA_ID = 1L;
    private static final Long USER_ID = 7L;

    private final AiService aiService = mock(AiService.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final RRateLimiter userLimiter = mock(RRateLimiter.class);
    private final RRateLimiter globalLimiter = mock(RRateLimiter.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private AnalysisDispatchService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisDispatchService(aiService, mediaService, redisTemplate,
                rocketMQTemplate, redissonClient, taskEventService, new VideoNoteProperties(), TOPIC);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, globalLimiter);
    }

    /**
     * 后台默认笔记必须使用独立的后台速率护栏（契约 §7.4 / D-049）。
     *
     * <p>复用交互式配额会让多单元合集从第 6 个单元起被拒绝，只能等恢复扫描逐轮补投。
     */
    @Test
    void backgroundSubmissionUsesDedicatedNoteLimiterInsteadOfInteractiveQuota() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);

        AnalysisDispatchService.SubmissionResult result = service.submitBackground(
                mediaFile(), DEFAULT_NOTE_GOAL, null, AnalysisMode.GENERAL);

        assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED, result);
        verify(redissonClient).getRateLimiter(AnalysisTaskKeys.noteUserLimit(USER_ID));
        verify(redissonClient).getRateLimiter(AnalysisTaskKeys.noteGlobalLimit());
        verify(redissonClient, never()).getRateLimiter("limit:ai:user:" + USER_ID);
        verify(redissonClient, never()).getRateLimiter("limit:ai:global");
    }

    /** 后台速率被拒时同样是"延迟重投"语义，不能变成永久失败。 */
    @Test
    void backgroundSubmissionIsRateLimitedWithoutSendingMessage() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userLimiter.tryAcquire()).thenReturn(false);

        AnalysisDispatchService.SubmissionResult result = service.submitBackground(
                mediaFile(), DEFAULT_NOTE_GOAL, null, AnalysisMode.GENERAL);

        assertEquals(AnalysisDispatchService.SubmissionResult.RATE_LIMITED, result);
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(AnalysisTaskMsg.class));
        // 被拒绝时必须释放活跃键，否则同一个单元再也投不进去。
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void acceptedSubmissionReusesExistingAnalysisMessageContract() {
        stubAcceptedAndWithinQuota();

        AnalysisDispatchService.SubmissionResult result =
                service.submit(mediaFile(), DEFAULT_NOTE_GOAL, null, AnalysisMode.GENERAL);

        assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED, result);
        ArgumentCaptor<AnalysisTaskMsg> message = ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(rocketMQTemplate).convertAndSend(eq(TOPIC), message.capture());
        assertEquals(MEDIA_ID, message.getValue().getMediaId());
        assertEquals(AnalysisTaskMsg.START_ANALYSIS, message.getValue().getAction());
        assertEquals(MD5, message.getValue().getContentHash());
        assertEquals(DEFAULT_NOTE_GOAL, message.getValue().getUserGoal());
        assertEquals(AnalysisMode.GENERAL.name(), message.getValue().getMode());
        verify(valueOperations).setIfAbsent(
                eq(AnalysisTaskKeys.active(MD5, AnalysisTaskKeys.goalDigest(DEFAULT_NOTE_GOAL, AnalysisMode.GENERAL))),
                eq(String.valueOf(MEDIA_ID)),
                any(Duration.class));
    }

    @Test
    void duplicateSubmissionIsReportedWithoutSendingMessageOrSpendingQuota() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        AnalysisDispatchService.SubmissionResult result =
                service.submit(mediaFile(), DEFAULT_NOTE_GOAL, null, AnalysisMode.GENERAL);

        assertEquals(AnalysisDispatchService.SubmissionResult.DUPLICATE, result);
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(AnalysisTaskMsg.class));
        verify(userLimiter, never()).tryAcquire();
    }

    @Test
    void rateLimitedSubmissionReleasesActiveKey() {
        stubAccepted();
        when(userLimiter.tryAcquire()).thenReturn(false);

        AnalysisDispatchService.SubmissionResult result =
                service.submit(mediaFile(), DEFAULT_NOTE_GOAL, null, AnalysisMode.GENERAL);

        assertEquals(AnalysisDispatchService.SubmissionResult.RATE_LIMITED, result);
        verify(redisTemplate).delete(activeKey());
        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(AnalysisTaskMsg.class));
    }

    @Test
    void dispatchFailureReleasesActiveKeyAndReportsFailure() {
        stubAcceptedAndWithinQuota();
        doThrow(new IllegalStateException("mq down"))
                .when(rocketMQTemplate).convertAndSend(eq(TOPIC), any(AnalysisTaskMsg.class));

        AnalysisDispatchService.SubmissionResult result =
                service.submit(mediaFile(), DEFAULT_NOTE_GOAL, null, AnalysisMode.GENERAL);

        assertEquals(AnalysisDispatchService.SubmissionResult.FAILED, result);
        verify(redisTemplate).delete(activeKey());
    }

    @Test
    void notificationFailureDoesNotTurnAcceptedSubmissionIntoFailure() {
        stubAcceptedAndWithinQuota();
        doThrow(new IllegalStateException("sse down"))
                .when(taskEventService).publishAnalysis(anyLong(), anyString(), any(), any(), any());

        AnalysisDispatchService.SubmissionResult result =
                service.submit(mediaFile(), DEFAULT_NOTE_GOAL, null, AnalysisMode.GENERAL);

        assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED, result);
        verify(rocketMQTemplate).convertAndSend(eq(TOPIC), any(AnalysisTaskMsg.class));
    }

    private MediaFile mediaFile() {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(MEDIA_ID);
        mediaFile.setUserId(USER_ID);
        mediaFile.setFilePath("http://minio/media/object.mp4");
        return mediaFile;
    }

    private void stubAccepted() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(MD5);
    }

    private void stubAcceptedAndWithinQuota() {
        stubAccepted();
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);
    }

    private String activeKey() {
        return AnalysisTaskKeys.active(
                MD5, AnalysisTaskKeys.goalDigest(DEFAULT_NOTE_GOAL, AnalysisMode.GENERAL));
    }
}
