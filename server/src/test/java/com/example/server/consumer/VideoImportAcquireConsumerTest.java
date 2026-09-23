package com.example.server.consumer;

import com.example.server.dto.VideoImportAcquireMessage;
import com.example.server.service.ingest.ImportAcquireService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit 获取消息的消费边界（D-064）。
 *
 * <p>本类只做两件事：结构性校验（毒消息落失败主题）和把下载交给有并发上限的专用执行器。
 * 三条硬约束：① 消费线程不下载；② 队列打满时**不改状态、确认消息**（媒体保持 QUEUED，由恢复扫描重投）；
 * ③ 毒消息必须落失败主题才允许确认，失败主题不可用时拒绝确认，避免消息静默丢失。
 */
class VideoImportAcquireConsumerTest {

    private static final Long MEDIA_ID = 21L;

    private final ImportAcquireService acquireService = mock(ImportAcquireService.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final String deadTopic = "video-import-acquire-dead-topic";

    /** 同步执行器：把"交给专用池"变成可直接断言的调用。 */
    private final Executor sameThread = Runnable::run;

    private final VideoImportAcquireConsumer consumer =
            new VideoImportAcquireConsumer(acquireService, rocketMQTemplate, sameThread, deadTopic);

    @Test
    void validMessageIsHandedToDownloadExecutor() {
        consumer.onMessage(VideoImportAcquireMessage.of(MEDIA_ID, "trace-1"));

        verify(acquireService).acquire(MEDIA_ID);
    }

    /** 队列打满：不改任何状态、不抛异常（消费线程确认消息），由恢复扫描重投。 */
    @Test
    void rejectedDownloadIsAckedWithoutThrowing() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };
        VideoImportAcquireConsumer saturated =
                new VideoImportAcquireConsumer(acquireService, rocketMQTemplate, rejecting, deadTopic);

        assertDoesNotThrow(() -> saturated.onMessage(VideoImportAcquireMessage.of(MEDIA_ID, "trace-1")));

        verify(acquireService, never()).acquire(anyLong());
    }

    @Test
    void nullMessageIsDiscardedToDeadTopic() {
        consumer.onMessage(null);

        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(acquireService, never()).acquire(anyLong());
    }

    @Test
    void unsupportedVersionIsDiscardedToDeadTopic() {
        VideoImportAcquireMessage unsupported = new VideoImportAcquireMessage(99, MEDIA_ID, "trace-1");

        consumer.onMessage(unsupported);

        verify(rocketMQTemplate).convertAndSend(eq(deadTopic), eq(unsupported));
        verify(acquireService, never()).acquire(anyLong());
    }

    @Test
    void messageWithoutMediaIdIsDiscardedToDeadTopic() {
        VideoImportAcquireMessage broken = new VideoImportAcquireMessage(
                VideoImportAcquireMessage.CURRENT_VERSION, null, "trace-1");

        consumer.onMessage(broken);

        verify(rocketMQTemplate).convertAndSend(eq(deadTopic), eq(broken));
        verify(acquireService, never()).acquire(anyLong());
    }

    /** 失败主题也不可用时必须拒绝确认，否则毒消息会被静默丢弃。 */
    @Test
    void poisonMessageMustNotBeAckedWhenDeadTopicIsDown() {
        doThrow(new IllegalStateException("broker down"))
                .when(rocketMQTemplate).convertAndSend(anyString(), any(Object.class));
        VideoImportAcquireMessage poison = new VideoImportAcquireMessage(99, MEDIA_ID, "trace-1");

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(poison));
    }
}
