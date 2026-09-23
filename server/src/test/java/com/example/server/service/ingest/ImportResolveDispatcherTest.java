package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.dto.VideoImportResolveMessage;
import com.example.server.entity.VideoImportJob;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.VideoImportJobMapper;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessagingException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * 解析消息投递契约：三种结果分别处理，首次提交与用户重试共用同一实现。
 *
 * <p>“不确定”这一分支存在的原因：发送超时或连接中断时 Broker 可能已经收单，按明确失败处理会把
 * 已经在处理的任务标成投递失败，因此保留 {@code PENDING_DISPATCH} 交给恢复扫描。
 */
class ImportResolveDispatcherTest {

    private static final String TOPIC = "video-import-resolve-topic";
    private static final Long IMPORT_ID = 10L;
    private static final Long USER_ID = 7L;
    private static final String REQUEST_HASH = "hash-value";

    private final VideoImportJobMapper jobMapper = mock(VideoImportJobMapper.class);
    private final ImportRequestCache requestCache = mock(ImportRequestCache.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);

    private ImportResolveDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ImportResolveDispatcher(jobMapper, requestCache, rocketMQTemplate, TOPIC);
    }

    @Test
    void successfulSendMovesJobToQueuedAndRemembersRequestMapping() {
        boolean dispatched = dispatcher.dispatch(job());

        assertTrue(dispatched);
        ArgumentCaptor<VideoImportResolveMessage> message =
                ArgumentCaptor.forClass(VideoImportResolveMessage.class);
        verify(rocketMQTemplate).convertAndSend(eq(TOPIC), message.capture());
        assertEquals(IMPORT_ID, message.getValue().importId());
        assertNotNull(message.getValue().traceId());
        assertTrue(message.getValue().isSupportedVersion());
        verify(jobMapper).casStatus(IMPORT_ID, VideoImportJobStatus.PENDING_DISPATCH, VideoImportJobStatus.QUEUED);
        verify(requestCache).remember(USER_ID, REQUEST_HASH, IMPORT_ID);
    }

    @Test
    void explicitRejectionMarksDispatchFailedReleasesActiveKeyAndReturns503() {
        doThrow(new MessagingException("send failed", new MQClientException(2, "send failed")))
                .when(rocketMQTemplate).convertAndSend(eq(TOPIC), any(VideoImportResolveMessage.class));

        BusinessException error = assertThrows(BusinessException.class, () -> dispatcher.dispatch(job()));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.errorCode());
        verify(jobMapper).casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.PENDING_DISPATCH, VideoImportJobStatus.DISPATCH_FAILED);
        verify(requestCache).forget(USER_ID, REQUEST_HASH);
        verify(jobMapper, never()).casStatus(anyLong(), any(), any());
    }

    @Test
    void uncertainOutcomeKeepsPendingDispatchForRecoveryScan() {
        doThrow(new MessagingException("send timeout", new RemotingException("send timeout")))
                .when(rocketMQTemplate).convertAndSend(eq(TOPIC), any(VideoImportResolveMessage.class));

        boolean dispatched = dispatcher.dispatch(job());

        assertFalse(dispatched);
        verify(jobMapper, never()).casStatusAndReleaseActiveKey(anyLong(), any(), any());
        verify(jobMapper, never()).casStatus(anyLong(), any(), any());
        verify(requestCache, never()).remember(anyLong(), anyString(), anyLong());
    }

    private VideoImportJob job() {
        VideoImportJob job = new VideoImportJob();
        job.setId(IMPORT_ID);
        job.setUserId(USER_ID);
        job.setRequestHash(REQUEST_HASH);
        job.setTraceId("trace-10");
        return job;
    }
}
