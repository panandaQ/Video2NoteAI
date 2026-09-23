package com.example.server.service.ingest;

import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 共享字节就绪的唤醒逻辑（D-068）。
 *
 * <p>这里固定四条边界：只为真正的等待者干活、不在订阅线程里做重活（交给有界执行器）、
 * 不信任消息内容、池满时放弃而不是抛错（恢复扫描兜底）。
 */
class ContentAssetReadyListenerTest {

    private static final Long ASSET_ID = 5L;
    private static final Long MEDIA_A = 41L;
    private static final Long MEDIA_B = 42L;

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final ImportAcquireService acquireService = mock(ImportAcquireService.class);

    /** 直接在当前线程执行，便于断言"每个等待者都被推进了一次"。 */
    private final Executor directExecutor = Runnable::run;

    private final ContentAssetReadyListener listener =
            new ContentAssetReadyListener(mediaFileMapper, acquireService, directExecutor);

    @Test
    void notifiesEveryWaiterThroughTheAcquireEntryPoint() {
        when(mediaFileMapper.findAwaitingSharedBytes(ASSET_ID,
                ContentAssetReadyListener.MAX_WAITERS_PER_NOTIFICATION))
                .thenReturn(List.of(MEDIA_A, MEDIA_B));

        listener.onMessage(message("5"), null);

        // 复用已有的获取入口：它自己会判断共享字节是否可用，这里不引入第二套状态机。
        verify(acquireService).acquire(MEDIA_A);
        verify(acquireService).acquire(MEDIA_B);
    }

    @Test
    void noWaitersMeansNoWork() {
        when(mediaFileMapper.findAwaitingSharedBytes(anyLong(), anyInt())).thenReturn(List.of());

        listener.onMessage(message("5"), null);

        verifyNoInteractions(acquireService);
    }

    @Test
    void malformedMessageIsIgnored() {
        listener.onMessage(message("not-an-id"), null);

        verify(mediaFileMapper, never()).findAwaitingSharedBytes(anyLong(), anyInt());
        verifyNoInteractions(acquireService);
    }

    /** 订阅线程不能因为池满而抛错：这条通知丢了，恢复扫描照样把等待者收敛掉。 */
    @Test
    void rejectedExecutionIsSwallowed() {
        when(mediaFileMapper.findAwaitingSharedBytes(anyLong(), anyInt())).thenReturn(List.of(MEDIA_A));
        Executor rejecting = task -> {
            throw new RejectedExecutionException("pool full");
        };
        ContentAssetReadyListener saturated =
                new ContentAssetReadyListener(mediaFileMapper, acquireService, rejecting);

        assertDoesNotThrow(() -> saturated.onMessage(message("5"), null));

        verify(acquireService, never()).acquire(anyLong());
    }

    @Test
    void databaseFailureDoesNotBreakTheSubscriptionThread() {
        doThrow(new IllegalStateException("db down"))
                .when(mediaFileMapper).findAwaitingSharedBytes(anyLong(), anyInt());

        assertDoesNotThrow(() -> listener.onMessage(message("5"), null));
    }

    private Message message(String body) {
        return new DefaultMessage("dovideo:content-ready".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
