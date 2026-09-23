package com.example.server.service.ingest;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.service.AnalysisLifecycleListener;
import com.example.server.service.MediaIndexService;
import com.example.server.service.MediaService;
import com.example.server.utils.AnalysisTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把默认视频笔记的分析生命周期映射回导入状态。
 *
 * <p>只有 {@link VideoNoteProfile} 定义的固定任务身份才影响导入父任务：用户自定义目标即使成功也不改变
 * 导入状态（契约 §7.4）。判断依据是目标摘要，避免依赖文案比较。
 *
 * <p>状态推进一律使用 CAS：任务开始 {@code ANALYSIS_QUEUED → ANALYZING}，完成 {@code ANALYZING → READY}，
 * 可重试失败回到 {@code ANALYSIS_QUEUED}，确定性失败或耗尽转 {@code FAILED}；媒体状态改变后再同步所有
 * 非终态子项并重算受影响父任务。
 */
@Component
public class VideoImportNoteLifecycle implements AnalysisLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(VideoImportNoteLifecycle.class);

    private final MediaFileMapper mediaFileMapper;
    private final VideoImportItemMapper itemMapper;
    private final ImportJobAggregator aggregator;
    private final MediaService mediaService;
    private final MediaIndexService mediaIndexService;
    private final String defaultNoteDigest;

    public VideoImportNoteLifecycle(MediaFileMapper mediaFileMapper,
                                    VideoImportItemMapper itemMapper,
                                    ImportJobAggregator aggregator,
                                    MediaService mediaService,
                                    MediaIndexService mediaIndexService) {
        this.mediaFileMapper = mediaFileMapper;
        this.itemMapper = itemMapper;
        this.aggregator = aggregator;
        this.mediaService = mediaService;
        this.mediaIndexService = mediaIndexService;
        this.defaultNoteDigest = AnalysisTaskKeys.goalDigest(VideoNoteProfile.GOAL, VideoNoteProfile.MODE);
    }

    @Override
    public void onStarted(Long mediaId, String goal, AnalysisMode mode) {
        if (!isDefaultNote(goal, mode)) return;
        // 中间态只写媒体行：子项不跟随 ANALYZING（D-066），终态时一次同步。
        casStatus(mediaId, MediaImportStatus.ANALYSIS_QUEUED, MediaImportStatus.ANALYZING, false, null);
    }

    @Override
    public void onCompleted(Long mediaId, String goal, AnalysisMode mode, boolean verified) {
        if (!isDefaultNote(goal, mode)) return;
        // 完成边界包含检索索引（D-069）：笔记好了但分块/向量还没就绪时不得进入 READY，
        // 否则用户收到"完成"通知后提问会检索不到任何证据。分块能从已落盘的上下文补建，
        // 连上下文都没有（例如崩溃在上下文之前）就保持处理中，由恢复扫描重投（AC-10）。
        if (!mediaIndexService.ensureIndexed(mediaId)) {
            log.warn("video_import_note_index_pending mediaId={}", mediaId);
            return;
        }
        // 从 ANALYSIS_QUEUED 直接完成也允许：复用已有结果的路径可能没有经过“开始处理”。
        if (!casStatus(mediaId, MediaImportStatus.ANALYZING, MediaImportStatus.READY, false, null)
                && !casStatus(mediaId, MediaImportStatus.ANALYSIS_QUEUED, MediaImportStatus.READY, false, null)
                && !casStatus(mediaId, MediaImportStatus.READY, MediaImportStatus.READY, false, null)) {
            // READY→READY 是 V1→V2 懒升级（D-078）：媒体全程保持 READY，只更新完成版本标记。
            log.info("video_import_note_completed_without_transition mediaId={}", mediaId);
            return;
        }
        // Critic 未通过也进 READY（D-104 产品口径：可用性优先），但不能悄悄清掉警告痕迹，
        // 否则用户和恢复扫描都无法区分"完全过检"与"带警告收尾"。
        if (verified) {
            mediaFileMapper.markNoteCompleted(mediaId,
                    MediaImportStatus.READY, MediaImportStatus.READY, VideoNoteProfile.VERSION);
        } else {
            mediaFileMapper.markNoteCompletedWithWarning(mediaId,
                    MediaImportStatus.READY, MediaImportStatus.READY, VideoNoteProfile.VERSION,
                    VideoImportErrorCode.NOTE_UNVERIFIED_EVIDENCE.name(),
                    VideoImportErrorCode.NOTE_UNVERIFIED_EVIDENCE.message());
        }
        syncTerminalItems(mediaId, MediaImportStatus.READY, false, null);
        invalidateMediaList(mediaId);
        log.info("video_import_note_completed mediaId={} profileVersion={} verified={}",
                mediaId, VideoNoteProfile.VERSION, verified);
    }

    @Override
    public void onRetryableFailure(Long mediaId, String goal, AnalysisMode mode) {
        if (!isDefaultNote(goal, mode)) return;
        // 回到 ANALYSIS_QUEUED 是中间态：只写媒体行，子项保持 PENDING_DISPATCH（D-066）。
        casStatus(mediaId, MediaImportStatus.ANALYZING, MediaImportStatus.ANALYSIS_QUEUED, true,
                VideoImportErrorCode.NOTE_PROCESSING_FAILED.name());
    }

    @Override
    public void onPermanentFailure(Long mediaId, String goal, AnalysisMode mode) {
        if (!isDefaultNote(goal, mode)) return;
        if (casStatus(mediaId, MediaImportStatus.ANALYZING, MediaImportStatus.FAILED, false,
                VideoImportErrorCode.NOTE_PROCESSING_REJECTED.name())
                || casStatus(mediaId, MediaImportStatus.ANALYSIS_QUEUED, MediaImportStatus.FAILED, false,
                VideoImportErrorCode.NOTE_PROCESSING_REJECTED.name())) {
            syncTerminalItems(mediaId, MediaImportStatus.FAILED, false,
                    VideoImportErrorCode.NOTE_PROCESSING_REJECTED.name());
            log.error("video_import_note_failed mediaId={}", mediaId);
        }
    }

    private boolean isDefaultNote(String goal, AnalysisMode mode) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        return defaultNoteDigest.equals(AnalysisTaskKeys.goalDigest(goal, mode));
    }

    private boolean casStatus(Long mediaId, MediaImportStatus expected, MediaImportStatus next,
                              boolean retryable, String errorCode) {
        try {
            if (mediaFileMapper.casStatus(mediaId, expected, next) == 0) {
                return false;
            }
            if (retryable || errorCode != null) {
                mediaFileMapper.markNoteDeferred(mediaId, next, VideoNoteProfile.VERSION,
                        errorCode, retryable ? VideoImportErrorCode.NOTE_PROCESSING_FAILED.message()
                                : VideoImportErrorCode.NOTE_PROCESSING_REJECTED.message());
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("video_import_note_transition_failed mediaId={} from={} to={}",
                    mediaId, expected, next, e);
            return false;
        }
    }

    private void syncTerminalItems(Long mediaId, MediaImportStatus terminalStatus,
                                   boolean retryable, String errorCode) {
        try {
            itemMapper.updatePendingToTerminalByMediaId(mediaId, terminalStatus, retryable, errorCode);
            aggregator.recomputeForMedia(mediaId);
        } catch (RuntimeException e) {
            log.warn("video_import_note_item_sync_failed mediaId={} status={}", mediaId, terminalStatus, e);
        }
    }

    /** 媒体进入列表可见集合后必须失效用户列表缓存，否则用户看到的还是旧列表。 */
    private void invalidateMediaList(Long mediaId) {
        try {
            MediaFile media = mediaFileMapper.selectById(mediaId);
            if (media != null) {
                mediaService.invalidateUserList(media.getUserId());
            }
        } catch (RuntimeException e) {
            log.warn("video_import_note_list_cache_invalidation_failed mediaId={}", mediaId, e);
        }
    }
}
