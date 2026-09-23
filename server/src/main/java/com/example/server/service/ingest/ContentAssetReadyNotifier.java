package com.example.server.service.ingest;

import com.example.server.utils.VideoImportKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 广播"某份共享内容的字节已经就绪"（D-068）。
 *
 * <p>它解决的是一个**延迟**问题，不是正确性问题：两个用户同时导入同一个新视频时，只有一个真的去下载，
 * 另一个在抢内容锁失败后原样返回，媒体停在 {@code ACQUIRING}。没有通知时，它要等恢复扫描下一轮
 * （最长约一个 stale 窗口 + 一个扫描间隔）才会发现自己要的字节其实已经下好了。
 *
 * <p>因此这里刻意做成"发不出去也无所谓"：Redis 抖动、没有订阅者、消息丢失，等待者都仍然在数据库里，
 * 恢复扫描按 {@code ACQUIRING} 的对象存在性把同一件事做完。**通知只让快的人更快，不让对的事情变对。**
 */
@Component
public class ContentAssetReadyNotifier {

    private static final Logger log = LoggerFactory.getLogger(ContentAssetReadyNotifier.class);

    private final StringRedisTemplate redisTemplate;

    public ContentAssetReadyNotifier(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 广播资产 ID；异常只记日志，绝不影响正在完成的这次导入。 */
    public void publish(Long assetId) {
        if (assetId == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(VideoImportKeys.contentReadyChannel(), String.valueOf(assetId));
            log.info("content_ready_notified assetId={}", assetId);
        } catch (RuntimeException e) {
            log.warn("content_ready_notify_failed assetId={}", assetId, e);
        }
    }
}
