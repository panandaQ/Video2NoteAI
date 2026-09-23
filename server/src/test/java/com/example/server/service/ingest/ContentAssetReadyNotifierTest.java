package com.example.server.service.ingest;

import com.example.server.utils.VideoImportKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 共享字节就绪广播契约（D-068）。
 *
 * <p>核心是"发不出去也不影响正确性"：这是加速通知，不是可靠性机制，所以任何 Redis 异常都必须被吞掉。
 */
class ContentAssetReadyNotifierTest {

    private static final Long ASSET_ID = 5L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ContentAssetReadyNotifier notifier = new ContentAssetReadyNotifier(redisTemplate);

    @Test
    void publishesAssetIdOnDedicatedChannel() {
        notifier.publish(ASSET_ID);

        verify(redisTemplate).convertAndSend(eq(VideoImportKeys.contentReadyChannel()), eq("5"));
    }

    @Test
    void redisFailureIsSwallowed() {
        doThrow(new IllegalStateException("redis down"))
                .when(redisTemplate).convertAndSend(anyString(), anyString());

        assertDoesNotThrow(() -> notifier.publish(ASSET_ID));
    }

    @Test
    void nullAssetIdIsIgnored() {
        notifier.publish(null);

        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }
}
