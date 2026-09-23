package com.example.server.service.ingest;

import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportAcquireMessage;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.entity.VideoImportItem;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.mapper.VideoImportJobMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 为一个 Unit 投递获取消息，并把媒体的待投递状态推进到 {@code QUEUED}。
 *
 * <p>投递结果是业务状态的一部分：明确成功才推进，明确失败写入 {@code DISPATCH_FAILED}，
 * 不允许留下“看起来正在处理”的记录（契约 §9）。重复解析消息只会重复投递同一 {@code mediaId}，
 * 由媒体状态 CAS 与 {@code READY} 快速返回吸收。
 */
@Service
public class ImportUnitDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ImportUnitDispatcher.class);

    /** 延迟重投的投递超时；与生产者统一的 3 秒量级。 */
    private static final long SEND_TIMEOUT_MS = 3_000L;

    /** RocketMQ 延迟级别 3 = 10 秒，与消费端重投的退避量级一致。 */
    public static final int RETRY_DELAY_LEVEL = 3;

    private final MediaFileMapper mediaFileMapper;
    private final VideoImportItemMapper itemMapper;
    private final VideoImportJobMapper jobMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final String acquireTopic;

    public ImportUnitDispatcher(MediaFileMapper mediaFileMapper,
                                VideoImportItemMapper itemMapper,
                                VideoImportJobMapper jobMapper,
                                RocketMQTemplate rocketMQTemplate,
                                @Value("${rocketmq.topic.video-import-acquire:video-import-acquire-topic}")
                                String acquireTopic) {
        this.mediaFileMapper = mediaFileMapper;
        this.itemMapper = itemMapper;
        this.jobMapper = jobMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.acquireTopic = acquireTopic;
    }

    /** @return 是否已成功投递 */
    public boolean dispatch(Long importId, Long mediaId, String traceId) {
        try {
            rocketMQTemplate.convertAndSend(acquireTopic,
                    VideoImportAcquireMessage.of(mediaId, traceId));
        } catch (RuntimeException e) {
            markDispatchFailed(importId, mediaId, e);
            return false;
        }
        int moved = mediaFileMapper.casStatus(mediaId,
                MediaImportStatus.PENDING_DISPATCH, MediaImportStatus.QUEUED);
        if (moved == 0) {
            // 投递是“先发消息、后推进状态”，消费者可能已经抢到消息并取得执行权。
            // 这不影响子项：子项不跟随中间态（D-066），只在 READY/FAILED 时同步。
            log.debug("video_import_unit_state_already_advanced importId={} mediaId={}", importId, mediaId);
        }
        log.info("video_import_unit_queued importId={} mediaId={} traceId={}", importId, mediaId, traceId);
        return true;
    }

    /**
     * 延迟重投：获取阶段的可重试失败用它替代"MQQ 消费端重投"。
     *
     * <p>下载被移出消费线程后，异常不会再冒泡给 RocketMQ，因此重投必须由我们显式完成。
     * 用 RocketMQ 的延迟消息（级别 3 = 10 秒，与消费端重投的退避量级一致）而不是立刻重发：
     * 立刻重发会让三次尝试在几秒内全部撞上同一个瞬时故障，退避才让重试有意义。
     *
     * <p>次数上限仍由 {@code media_files.acquire_attempt_count} 与 {@code video.import.max-attempts} 决定，
     * 调用方在预算耗尽时不得调用本方法。
     *
     * @return 是否已成功投递（未成功时媒体已被标记 DISPATCH_FAILED，由恢复扫描兜底）
     */
    public boolean dispatchDelayed(Long mediaId, int delayLevel) {
        Long importId = null;
        String traceId = null;
        for (VideoImportItem item : itemMapper.findByMediaId(mediaId)) {
            if (item.getImportId() != null) {
                importId = item.getImportId();
                break;
            }
        }
        VideoImportJob job = importId == null ? null : jobMapper.selectById(importId);
        traceId = job == null || job.getTraceId() == null ? "retry-" + mediaId : job.getTraceId();
        try {
            rocketMQTemplate.syncSend(acquireTopic,
                    MessageBuilder.withPayload(VideoImportAcquireMessage.of(mediaId, traceId)).build(),
                    SEND_TIMEOUT_MS, delayLevel);
        } catch (RuntimeException e) {
            markDispatchFailed(importId, mediaId, e);
            return false;
        }
        log.warn("video_import_unit_retry_scheduled mediaId={} importId={} delayLevel={}",
                mediaId, importId, delayLevel);
        return true;
    }

    private void markDispatchFailed(Long importId, Long mediaId, RuntimeException error) {
        // ITEM_DISPATCH_FAILED 是可重试错误码：恢复扫描或用户重试可以再次投递。
        itemMapper.casItemStatus(importId, mediaId,
                MediaImportStatus.PENDING_DISPATCH, MediaImportStatus.DISPATCH_FAILED,
                true, VideoImportErrorCode.ITEM_DISPATCH_FAILED.name());
        mediaFileMapper.casStatus(mediaId,
                MediaImportStatus.PENDING_DISPATCH, MediaImportStatus.DISPATCH_FAILED);
        log.error("video_import_unit_dispatch_failed importId={} mediaId={}", importId, mediaId, error);
    }
}
