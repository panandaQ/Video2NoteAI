package com.example.server.service.ingest;

import com.example.server.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 收到"共享字节已就绪"后把等待中的用户条目推进下去（D-068）。
 *
 * <p>处理方式刻意很薄：**查出还在等这份字节的媒体，然后逐个调用已有的获取入口**
 * （{@link ImportAcquireService#acquire}）。获取入口本身就是幂等的——它会先看共享资产有没有可用对象，
 * 有就把引用挂到自己条目上并推进状态，没有才下载。因此这里不需要任何新的状态机：
 * 通知只是把"本来要等扫描才发生的事"提前触发一次。
 *
 * <p>三个必须守住的边界：
 * <ul>
 *   <li><b>不在订阅线程里干活</b>：逐个媒体交给有界的下载执行器；池满（{@link RejectedExecutionException}）
 *       就放弃这一次，恢复扫描照旧兜底——不能让一条通知把 Redis 订阅线程堵住；</li>
 *   <li><b>不信任消息内容</b>：只接受能解析成正整数的资产 ID，其余当作噪声丢弃；</li>
 *   <li><b>有上限</b>：一次通知最多唤醒固定数量的等待者，避免热门内容被瞬间拉起成风暴。</li>
 * </ul>
 */
@Component
public class ContentAssetReadyListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ContentAssetReadyListener.class);

    /** 一次通知最多唤醒的等待者数量；超出的部分交给恢复扫描（它们本来就会被收敛）。 */
    static final int MAX_WAITERS_PER_NOTIFICATION = 50;

    private final MediaFileMapper mediaFileMapper;
    private final ImportAcquireService acquireService;
    private final Executor downloadExecutor;

    public ContentAssetReadyListener(MediaFileMapper mediaFileMapper,
                                     ImportAcquireService acquireService,
                                     @Qualifier("videoDownloadExecutor") Executor downloadExecutor) {
        this.mediaFileMapper = mediaFileMapper;
        this.acquireService = acquireService;
        this.downloadExecutor = downloadExecutor;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        Long assetId = parseAssetId(message.getBody());
        if (assetId == null) {
            log.warn("content_ready_message_invalid");
            return;
        }
        List<Long> waiting;
        try {
            waiting = mediaFileMapper.findAwaitingSharedBytes(assetId, MAX_WAITERS_PER_NOTIFICATION);
        } catch (RuntimeException e) {
            log.warn("content_ready_waiters_query_failed assetId={}", assetId, e);
            return;
        }
        if (waiting == null || waiting.isEmpty()) {
            return;
        }
        log.info("content_ready_waiters_notified assetId={} waiters={}", assetId, waiting.size());
        for (Long mediaId : waiting) {
            try {
                downloadExecutor.execute(() -> acquireService.acquire(mediaId));
            } catch (RejectedExecutionException e) {
                log.warn("content_ready_dispatch_rejected assetId={} mediaId={}", assetId, mediaId);
            }
        }
    }

    private Long parseAssetId(byte[] body) {
        if (body == null) {
            return null;
        }
        try {
            long value = Long.parseLong(new String(body, StandardCharsets.UTF_8).trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
