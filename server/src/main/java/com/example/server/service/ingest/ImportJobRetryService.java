package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.dto.VideoImportSubmissionResponse;
import com.example.server.entity.MediaFile;
import com.example.server.entity.VideoImportItem;
import com.example.server.entity.VideoImportJob;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.utils.VideoImportKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 用户主动重试导入任务（契约 §3.4）。
 *
 * <p>只允许三类入口：{@code DISPATCH_FAILED}、标记为可重试的 {@code FAILED}、以及
 * {@code PARTIAL_SUCCESS} 中失败且可重试的子项。重试通过数据库 CAS 取得执行权并复用原 {@code importId}，
 * 不新建父任务。
 *
 * <p>{@code PARTIAL_SUCCESS} 只重投失败的单元：已经到达 {@code MEDIA_READY} 及之后的单元不会重新下载，
 * 它们的媒体引用继续复用。
 */
@Service
public class ImportJobRetryService {

    private static final Logger log = LoggerFactory.getLogger(ImportJobRetryService.class);

    private final VideoImportJobMapper jobMapper;
    private final VideoImportItemMapper itemMapper;
    private final MediaFileMapper mediaFileMapper;
    private final ImportUnitDispatcher unitDispatcher;
    private final ImportJobAggregator aggregator;
    private final ImportResolveDispatcher resolveDispatcher;

    public ImportJobRetryService(VideoImportJobMapper jobMapper,
                                 VideoImportItemMapper itemMapper,
                                 MediaFileMapper mediaFileMapper,
                                 ImportUnitDispatcher unitDispatcher,
                                 ImportJobAggregator aggregator,
                                 ImportResolveDispatcher resolveDispatcher) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.unitDispatcher = unitDispatcher;
        this.aggregator = aggregator;
        this.resolveDispatcher = resolveDispatcher;
    }

    /**
     * @return 与创建接口相同的受理结果；复用其他活跃任务时 {@code reused=true}
     * @throws NoSuchElementException 任务不存在或不属于当前用户（404）
     * @throws BusinessException 409 当前状态不可重试
     */
    public VideoImportSubmissionResponse retry(Long userId, Long importId) {
        VideoImportJob job = jobMapper.findOwnedById(importId, userId);
        if (job == null) {
            throw new NoSuchElementException("导入任务不存在");
        }
        VideoImportJobStatus status = job.getStatus();
        if (status == VideoImportJobStatus.DISPATCH_FAILED) {
            return restartResolve(job);
        }
        if (status == VideoImportJobStatus.FAILED) {
            if (!Boolean.TRUE.equals(job.getRetryable())) {
                throw new BusinessException(ErrorCode.CONFLICT, "该失败不可重试");
            }
            return restartResolve(job);
        }
        if (status == VideoImportJobStatus.PARTIAL_SUCCESS) {
            return retryFailedUnits(job);
        }
        throw new BusinessException(ErrorCode.CONFLICT, "当前状态不可重试");
    }

    /** 从父任务重新解析：回到 {@code PENDING_DISPATCH} 并重新占用活跃键。 */
    private VideoImportSubmissionResponse restartResolve(VideoImportJob job) {
        String activeRequestKey = VideoImportKeys.activeRequestKey(job.getUserId(), job.getRequestHash());
        VideoImportJob other = jobMapper.findByActiveRequestKey(activeRequestKey);
        if (other != null && !other.getId().equals(job.getId()) && !other.getStatus().isTerminal()) {
            // 同一 URL 已有另一个活跃任务：返回那个任务，不重复解析（契约 §3.4）。
            log.info("video_import_retry_reused_other importId={} activeImportId={}",
                    job.getId(), other.getId());
            return VideoImportSubmissionResponse.of(other, true);
        }

        int moved;
        try {
            moved = jobMapper.casStatusAndAttachActiveKey(job.getId(), job.getStatus(),
                    VideoImportJobStatus.PENDING_DISPATCH, activeRequestKey);
        } catch (DuplicateKeyException e) {
            // 唯一键竞争：并发重试或并发提交抢先占用了活跃键。
            VideoImportJob winner = jobMapper.findByActiveRequestKey(activeRequestKey);
            if (winner == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "导入任务状态已变化，请刷新后重试");
            }
            return VideoImportSubmissionResponse.of(winner, true);
        }
        if (moved == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "导入任务状态已变化，请刷新后重试");
        }
        // 用户显式重试 = 重新给一次完整的自动恢复预算，否则恢复扫描会在下一轮立刻把任务判失败。
        jobMapper.resetAttempt(job.getId());

        VideoImportJob pending = jobMapper.findOwnedById(job.getId(), job.getUserId());
        resolveDispatcher.dispatch(pending == null ? job : pending);
        VideoImportJob current = jobMapper.findOwnedById(job.getId(), job.getUserId());
        log.info("video_import_retry_accepted importId={} attempt={}",
                job.getId(), current == null ? null : current.getAttemptCount());
        return VideoImportSubmissionResponse.of(current == null ? job : current, false);
    }

    /** 只重投可重试失败的单元，已入库单元保持原样。 */
    private VideoImportSubmissionResponse retryFailedUnits(VideoImportJob job) {
        if (jobMapper.casStatus(job.getId(), VideoImportJobStatus.PARTIAL_SUCCESS,
                VideoImportJobStatus.PROCESSING) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "导入任务状态已变化，请刷新后重试");
        }
        jobMapper.incrementAttempt(job.getId());

        int redispatched = 0;
        List<VideoImportItem> items = itemMapper.findByImportId(job.getId());
        for (VideoImportItem item : items) {
            if (!isRetryableFailure(item)) {
                continue;
            }
            if (resetFailedUnit(job, item)) {
                redispatched++;
            }
        }
        if (redispatched == 0) {
            // 没有可重投的子项：按子项重算，把父任务放回它应处的状态。
            aggregator.recompute(job.getId());
        }
        log.info("video_import_retry_units importId={} redispatched={} total={}",
                job.getId(), redispatched, items.size());
        VideoImportJob current = jobMapper.findOwnedById(job.getId(), job.getUserId());
        return VideoImportSubmissionResponse.of(current == null ? job : current, false);
    }

    private boolean isRetryableFailure(VideoImportItem item) {
        MediaImportStatus status = item.getItemStatus();
        if (status != MediaImportStatus.FAILED && status != MediaImportStatus.DISPATCH_FAILED) {
            return false;
        }
        return Boolean.TRUE.equals(item.getRetryable());
    }

    /**
     * 把失败单元放回待投递：媒体与子项一起 CAS 到 {@code PENDING_DISPATCH}，再投递获取消息。
     *
     * <p>媒体若已有对象引用，获取链路会跳过下载并直接回到 {@code MEDIA_READY}（见
     * {@code ImportAcquireService}），因此重试不会重复下载已入库的媒体。
     */
    private boolean resetFailedUnit(VideoImportJob job, VideoImportItem item) {
        Long mediaId = item.getMediaId();
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            log.warn("video_import_retry_media_missing importId={} mediaId={}", job.getId(), mediaId);
            return false;
        }
        MediaImportStatus mediaStatus = media.getStatus();
        if (mediaStatus == null
                || mediaFileMapper.casStatus(mediaId, mediaStatus, MediaImportStatus.PENDING_DISPATCH) == 0) {
            log.info("video_import_retry_media_not_reset importId={} mediaId={} status={}",
                    job.getId(), mediaId, mediaStatus);
            return false;
        }
        if (itemMapper.casItemStatus(job.getId(), mediaId, item.getItemStatus(),
                MediaImportStatus.PENDING_DISPATCH, true, null) == 0) {
            return false;
        }
        // 同父任务重试：把媒体的自动重投预算一并重置，让恢复扫描按新的尝试次数判断。
        mediaFileMapper.resetAcquireAttempt(mediaId);
        return unitDispatcher.dispatch(job.getId(), mediaId, job.getTraceId());
    }
}
