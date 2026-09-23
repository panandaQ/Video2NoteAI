package com.example.server.consumer;

import com.example.server.dto.VideoImportAcquireMessage;
import com.example.server.service.ingest.ImportAcquireService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Unit 获取消息的 MQ 边界。
 *
 * <p>只处理媒体获取：不接受用户分析目标、不调用 Agent。默认视频笔记由
 * {@link com.example.server.service.ingest.VideoNoteTaskPort} 在媒体入库后提交，本类不拼装分析消息。
 *
 * <p>消息不携带 {@code importId}：同一媒体可能属于多个历史导入任务，媒体状态才是获取阶段的事实来源。
 *
 * <p><b>消费线程与下载隔离</b>（D-064）：本类只做结构性校验与提交，真正的下载交给
 * {@code videoDownloadExecutor}（有并发上限的专用池）执行。原因是下载可能持续几分钟，跑在消费线程里
 * 会堵住后面的消息，而且没有并发上限时会出现几十个下载互相争抢带宽。代价是异常不再冒泡给 RocketMQ，
 * 因此获取阶段的可重试失败改为由 {@code ImportAcquireService} 显式延迟重投（有预算、有退避）。
 */
@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-import-acquire:video-import-acquire-topic}",
        consumerGroup = "${rocketmq.consumer.video-import-acquire-group:video-import-acquire-consumer}",
        maxReconsumeTimes = 2)
public class VideoImportAcquireConsumer implements RocketMQListener<VideoImportAcquireMessage> {

    private static final Logger log = LoggerFactory.getLogger(VideoImportAcquireConsumer.class);

    private final ImportAcquireService acquireService;
    private final RocketMQTemplate rocketMQTemplate;
    private final Executor downloadExecutor;
    private final String deadLetterTopic;

    public VideoImportAcquireConsumer(ImportAcquireService acquireService,
                                      RocketMQTemplate rocketMQTemplate,
                                      @Qualifier("videoDownloadExecutor") Executor downloadExecutor,
                                      @Value("${rocketmq.topic.video-import-acquire-dead:video-import-acquire-dead-topic}")
                                      String deadLetterTopic) {
        this.acquireService = acquireService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.downloadExecutor = downloadExecutor;
        this.deadLetterTopic = deadLetterTopic;
    }

    @Override
    public void onMessage(VideoImportAcquireMessage message) {
        String rejection = rejectionReason(message);
        if (rejection != null) {
            discardPoisonMessage(message, rejection);
            return;
        }
        try {
            downloadExecutor.execute(() -> acquireService.acquire(message.mediaId()));
        } catch (RejectedExecutionException e) {
            // 下载队列打满：不改任何状态、直接确认本条消息。
            // 媒体此时仍是 QUEUED，恢复扫描会在下个周期重投；宁可晚一点，也不让下载吃穿带宽与内存。
            log.warn("video_import_download_rejected mediaId={} traceId={} reason=queue_full",
                    message.mediaId(), message.traceId());
        }
    }

    private String rejectionReason(VideoImportAcquireMessage message) {
        if (message == null) return "消息体为空";
        if (!message.isSupportedVersion()) return "不支持的 version=" + message.version();
        if (message.mediaId() == null) return "缺少 mediaId";
        return null;
    }

    /** 结构性非法消息落失败主题后才允许确认；失败主题不可用时必须拒绝确认。 */
    private void discardPoisonMessage(VideoImportAcquireMessage message, String reason) {
        log.error("video_import_acquire_poison_message reason={} mediaId={}",
                reason, message == null ? null : message.mediaId());
        if (message == null) return;
        try {
            rocketMQTemplate.convertAndSend(deadLetterTopic, message);
        } catch (RuntimeException e) {
            log.error("video_import_acquire_poison_dead_letter_failed mediaId={}", message.mediaId(), e);
            throw new IllegalStateException("毒消息无法收敛：失败主题不可用，拒绝确认以避免消息丢失", e);
        }
    }
}
