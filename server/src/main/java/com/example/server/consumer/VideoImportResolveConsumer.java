package com.example.server.consumer;

import com.example.server.dto.VideoImportResolveMessage;
import com.example.server.service.ingest.ImportResolveService;
import com.example.server.source.VideoSourceException;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * URL 解析消息的 MQ 边界。
 *
 * <p>只负责消息校验、异常确认策略和日志上下文；解析、登记与投递都在 {@link ImportResolveService}。
 * Consumer 必须可重复执行：执行权由数据库状态 CAS 给出，同一 importId 的重复消息不会重复解析。
 *
 * <p>与现有 {@code VideoAnalysisConsumer} 保持同一套模式与投递次数上限（2 次重投 = 最多 3 次投递），
 * 但不为导入链路新建失败台账表：错误写入父任务与子项的错误码字段（契约 §11）。
 */
@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-import-resolve:video-import-resolve-topic}",
        consumerGroup = "${rocketmq.consumer.video-import-resolve-group:video-import-resolve-consumer}",
        maxReconsumeTimes = 2)
public class VideoImportResolveConsumer implements RocketMQListener<VideoImportResolveMessage> {

    private static final Logger log = LoggerFactory.getLogger(VideoImportResolveConsumer.class);

    private final ImportResolveService resolveService;
    private final RocketMQTemplate rocketMQTemplate;
    private final String deadLetterTopic;

    public VideoImportResolveConsumer(ImportResolveService resolveService,
                                      RocketMQTemplate rocketMQTemplate,
                                      @Value("${rocketmq.topic.video-import-resolve-dead:video-import-resolve-dead-topic}")
                                      String deadLetterTopic) {
        this.resolveService = resolveService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.deadLetterTopic = deadLetterTopic;
    }

    @Override
    public void onMessage(VideoImportResolveMessage message) {
        String rejection = rejectionReason(message);
        if (rejection != null) {
            discardPoisonMessage(message, rejection);
            return;
        }
        try {
            resolveService.resolve(message.importId());
        } catch (VideoSourceException e) {
            if (e.retryable()) {
                // 可重试来源异常：状态已放回 QUEUED，抛出让 RocketMQ 有限重投。
                log.warn("video_import_resolve_retry_scheduled importId={} errorCode={} traceId={}",
                        message.importId(), e.errorCode().name(), message.traceId());
                throw new IllegalStateException("视频导入解析失败，交由 RocketMQ 重试", e);
            }
            // 不可重试的失败已在服务层写入父任务状态，这里正常确认。
            log.warn("video_import_resolve_rejected importId={} errorCode={} traceId={}",
                    message.importId(), e.errorCode().name(), message.traceId());
        } catch (RuntimeException e) {
            log.warn("video_import_resolve_failed importId={} traceId={}",
                    message.importId(), message.traceId(), e);
            throw new IllegalStateException("视频导入解析消费失败，交由 RocketMQ 重试", e);
        }
    }

    /** 结构性校验：返回拒绝原因，合法则返回 null。 */
    private String rejectionReason(VideoImportResolveMessage message) {
        if (message == null) return "消息体为空";
        if (!message.isSupportedVersion()) return "不支持的 version=" + message.version();
        if (message.importId() == null) return "缺少 importId";
        return null;
    }

    /**
     * 毒消息收敛：结构性非法的消息重投多少次都不会变好，落失败主题后正常确认。
     * 失败主题也不可用时必须拒绝确认，否则消息会被静默丢弃。
     */
    private void discardPoisonMessage(VideoImportResolveMessage message, String reason) {
        log.error("video_import_resolve_poison_message reason={} importId={}",
                reason, message == null ? null : message.importId());
        if (message == null) return;
        try {
            rocketMQTemplate.convertAndSend(deadLetterTopic, message);
        } catch (RuntimeException e) {
            log.error("video_import_poison_dead_letter_failed importId={}", message.importId(), e);
            throw new IllegalStateException("毒消息无法收敛：失败主题不可用，拒绝确认以避免消息丢失", e);
        }
    }
}
