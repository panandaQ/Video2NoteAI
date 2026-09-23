package com.example.server.service.ingest;

import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.entity.VideoImportItem;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.mapper.VideoImportJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 从导入子项重算父任务的计数与状态。
 *
 * <p>{@code video_import_items} 是计数事实来源，父表计数只是查询优化：任何一次子项状态推进之后都调用本类，
 * 进程在子项提交与父计数更新之间退出时也能靠重算修复。
 *
 * <p>父任务只有全部 Unit 的默认笔记完成后才进入 {@code COMPLETED}；只要有子项仍在处理中就是
 * {@code PROCESSING}，绝不提前宣告成功。
 */
@Service
public class ImportJobAggregator {

    private static final Logger log = LoggerFactory.getLogger(ImportJobAggregator.class);

    private final VideoImportJobMapper jobMapper;
    private final VideoImportItemMapper itemMapper;
    private final ImportRequestCache requestCache;
    private final ImportTerminalEventPublisher terminalPublisher;

    public ImportJobAggregator(VideoImportJobMapper jobMapper,
                               VideoImportItemMapper itemMapper,
                               ImportRequestCache requestCache,
                               ImportTerminalEventPublisher terminalPublisher) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.requestCache = requestCache;
        this.terminalPublisher = terminalPublisher;
    }

    /**
     * 重算所有引用了该媒体的父任务。
     *
     * <p>媒体获取消息不携带 {@code importId}，因为同一媒体可能同时属于多个历史导入任务；
     * 因此状态推进后按 mediaId 反查子项，再逐个重算父任务。
     */
    public void recomputeForMedia(Long mediaId) {
        List<VideoImportItem> items = itemMapper.findByMediaId(mediaId);
        items.stream()
                .map(VideoImportItem::getImportId)
                .distinct()
                .forEach(this::recompute);
    }

    /** 重算指定父任务；父任务不存在时直接返回，重复消息因此天然幂等。 */
    public void recompute(Long importId) {
        VideoImportJob job = jobMapper.selectById(importId);
        if (job == null) {
            log.warn("video_import_aggregate_job_missing importId={}", importId);
            return;
        }
        // 计数用一条聚合查询，不再把全部子项读进内存：单元数为 N 时，
        // 每个单元约 6 次状态推进，旧的"全量读 + 内存 count"总读取量是 O(N²)。
        List<VideoImportItemMapper.ItemStatusCount> counts = itemMapper.countByStatusGrouped(importId);
        if (counts.isEmpty()) {
            return;
        }
        int total = 0;
        int reused = 0;
        int ready = 0;
        int failed = 0;
        int completed = 0;
        for (VideoImportItemMapper.ItemStatusCount count : counts) {
            int rows = (int) count.total();
            total += rows;
            reused += (int) count.reused();
            MediaImportStatus status = parseStatus(count.status());
            if (status == MediaImportStatus.READY) {
                ready += rows;
            } else if (status == MediaImportStatus.FAILED) {
                failed += rows;
            } else if (status == MediaImportStatus.COMPLETED) {
                completed += rows;
            }
        }

        // 计数没变就不写：省掉"每次推进都重写父任务"里绝大部分冗余写。
        // 它还切断了与恢复扫描的相互触发——写计数会刷新 updated_at，让一个没有实质变化的中间态
        // 反复被判定为"超时未推进"，形成"对账 → 写 → 再对账"的循环。
        if (!countsUnchanged(job, total, reused, ready, failed)) {
            jobMapper.updateCounts(importId, total, reused, ready, failed);
        }

        VideoImportJobStatus target = targetStatus(total, ready, failed, completed);
        VideoImportJobStatus current = job.getStatus();
        if (target == null || target == current) {
            return;
        }
        if (!current.canTransitionTo(target)) {
            reconcileTerminalCompletion(importId, job, current, target, total, ready, failed);
            return;
        }
        if (target.isTerminal()) {
            // 终态必须同时清空活跃键，否则相同 URL 无法再次提交。
            // 发布必须以"这次 CAS 真的写成功了"为前提：并发/重复消息里失败的一侧不能再发一次终态，
            // 否则客户端会收到重复通知（前端需幂等，但服务端不该制造）。
            boolean written = jobMapper.casStatusAndReleaseActiveKey(importId, current, target) > 0;
            requestCache.forget(job.getUserId(), job.getRequestHash());
            if (written) {
                publishTerminal(importId, target);
            }
        } else {
            jobMapper.casStatus(importId, current, target);
        }
        log.info("video_import_job_aggregated importId={} from={} to={} total={} completed={} failed={}",
                importId, current, target, total, ready, failed);
    }

    private MediaImportStatus parseStatus(String value) {
        try {
            return MediaImportStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** 四个计数与行上现有值完全一致时无需回写。 */
    private boolean countsUnchanged(VideoImportJob job, int total, int reused, int ready, int failed) {
        return value(job.getTotalCount()) == total
                && value(job.getReusedCount()) == reused
                && value(job.getCompletedCount()) == ready
                && value(job.getFailedCount()) == failed;
    }

    private int value(Integer stored) {
        return stored == null ? 0 : stored;
    }

    /**
     * 终态与子项事实矛盾时的收敛。
     *
     * <p>状态机不允许终态回退，因此 {@code FAILED/PARTIAL_SUCCESS} 到 {@code COMPLETED} 默认被拒绝。
     * 但“全部子项都已 {@code READY}”是更强的事实：这时父任务停在失败态会让查询接口返回
     * {@code status=FAILED, completedCount=N, failedCount=0} 这种自相矛盾的结果，而视频其实已经在用户库里。
     * 恢复扫描检出这种行后，允许按子项事实收敛为 {@code COMPLETED} 并清空失败文案（D-052）。
     */
    private void reconcileTerminalCompletion(Long importId,
                                             VideoImportJob job,
                                             VideoImportJobStatus current,
                                             VideoImportJobStatus target,
                                             int total,
                                             int completed,
                                             int failed) {
        if (target != VideoImportJobStatus.COMPLETED || !current.isTerminal()) {
            return;
        }
        if (jobMapper.casStatusAndReleaseActiveKey(importId, current, target) == 0) {
            return;
        }
        jobMapper.updateError(importId, null, null, false);
        requestCache.forget(job.getUserId(), job.getRequestHash());
        terminalPublisher.publishCompleted(importId);
        log.warn("video_import_job_reconciled_to_completed importId={} from={} total={} completed={} failed={}",
                importId, current, total, completed, failed);
    }

    /**
     * 父任务终态的对外投影：{@code COMPLETED} 发完成，{@code PARTIAL_SUCCESS} 发受控的部分失败文案，
     * 其余终态（{@code FAILED}）发失败。单视频场景只有前两者之一。
     */
    private void publishTerminal(Long importId, VideoImportJobStatus target) {
        if (target == VideoImportJobStatus.COMPLETED) {
            terminalPublisher.publishCompleted(importId);
        } else if (target == VideoImportJobStatus.PARTIAL_SUCCESS) {
            terminalPublisher.publishPartial(importId);
        } else {
            terminalPublisher.publishFailed(importId);
        }
    }

    /**
     * 从聚合计数推导父任务目标状态。
     *
     * <p>{@code inFlight = total - ready - failed - completed}：{@code COMPLETED} 是旧上传记录的终态，
     * 也算终态，所以必须从"在飞"里扣掉，否则父任务会永远停在 {@code PROCESSING}。
     */
    private VideoImportJobStatus targetStatus(int total, int ready, int failed, int completed) {
        if (ready == total) {
            return VideoImportJobStatus.COMPLETED;
        }
        if (failed == total) {
            return VideoImportJobStatus.FAILED;
        }
        boolean anyInFlight = total - ready - failed - completed > 0;
        if (anyInFlight) {
            return VideoImportJobStatus.PROCESSING;
        }
        return failed > 0 ? VideoImportJobStatus.PARTIAL_SUCCESS : VideoImportJobStatus.PROCESSING;
    }
}
