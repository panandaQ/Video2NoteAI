package com.example.server.consumer;

import com.example.server.dto.VideoImportErrorCode;
import com.example.server.dto.VideoImportResolveMessage;
import com.example.server.service.ingest.ImportResolveService;
import com.example.server.source.VideoSourceException;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessagingException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 解析消费者边界契约：毒消息收敛、可重试异常交 MQ、不可重试异常正常确认。
 *
 * <p>与 {@code VideoAnalysisConsumer} 一致，结构性非法消息必须留下可追溯记录后才允许确认。
 */
class VideoImportResolveConsumerTest {

    private static final String DEAD_TOPIC = "video-import-resolve-dead-topic";
    private static final Long IMPORT_ID = 10L;

    private final ImportResolveService resolveService = mock(ImportResolveService.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);

    private final VideoImportResolveConsumer consumer =
            new VideoImportResolveConsumer(resolveService, rocketMQTemplate, DEAD_TOPIC);

    @Test
    void validMessageIsDelegatedToResolveService() {
        VideoImportResolveMessage message = VideoImportResolveMessage.of(IMPORT_ID, "trace-10");

        assertDoesNotThrow(() -> consumer.onMessage(message));

        verify(resolveService).resolve(IMPORT_ID);
        verify(rocketMQTemplate, never()).convertAndSend(eq(DEAD_TOPIC), any(VideoImportResolveMessage.class));
    }

    @Test
    void unsupportedVersionIsDeadLetteredAndAcked() {
        VideoImportResolveMessage message = new VideoImportResolveMessage(99, IMPORT_ID, "trace-10");

        assertDoesNotThrow(() -> consumer.onMessage(message));

        verify(resolveService, never()).resolve(anyLong());
        verify(rocketMQTemplate).convertAndSend(DEAD_TOPIC, message);
    }

    @Test
    void messageWithoutImportIdIsRejected() {
        VideoImportResolveMessage message = new VideoImportResolveMessage(1, null, "trace-10");

        assertDoesNotThrow(() -> consumer.onMessage(message));

        verify(resolveService, never()).resolve(anyLong());
        verify(rocketMQTemplate).convertAndSend(DEAD_TOPIC, message);
    }

    @Test
    void nullMessageIsIgnoredWithoutDeadLetterAttempt() {
        assertDoesNotThrow(() -> consumer.onMessage(null));

        verify(resolveService, never()).resolve(anyLong());
        verify(rocketMQTemplate, never()).convertAndSend(any(), any(VideoImportResolveMessage.class));
    }

    @Test
    void retryableSourceFailureIsRethrownForRocketMqRedelivery() {
        VideoImportResolveMessage message = VideoImportResolveMessage.of(IMPORT_ID, "trace-10");
        doThrow(new VideoSourceException(VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE))
                .when(resolveService).resolve(IMPORT_ID);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(message));

        assertTrue(error.getMessage().contains("交由 RocketMQ 重试"));
        verify(rocketMQTemplate, never()).convertAndSend(eq(DEAD_TOPIC), any(VideoImportResolveMessage.class));
    }

    @Test
    void nonRetryableSourceFailureIsAcked() {
        VideoImportResolveMessage message = VideoImportResolveMessage.of(IMPORT_ID, "trace-10");
        doThrow(new VideoSourceException(VideoImportErrorCode.SOURCE_NOT_FOUND))
                .when(resolveService).resolve(IMPORT_ID);

        assertDoesNotThrow(() -> consumer.onMessage(message));

        verify(rocketMQTemplate, never()).convertAndSend(eq(DEAD_TOPIC), any(VideoImportResolveMessage.class));
    }

    @Test
    void unexpectedFailureIsRethrownForRetry() {
        VideoImportResolveMessage message = VideoImportResolveMessage.of(IMPORT_ID, "trace-10");
        doThrow(new IllegalStateException("db down")).when(resolveService).resolve(IMPORT_ID);

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message));
    }

    @Test
    void poisonMessageRefusesAckWhenDeadLetterUnavailable() {
        VideoImportResolveMessage message = new VideoImportResolveMessage(99, IMPORT_ID, "trace-10");
        doThrow(new MessagingException("mq down"))
                .when(rocketMQTemplate).convertAndSend(eq(DEAD_TOPIC), any(VideoImportResolveMessage.class));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message));
    }
}
