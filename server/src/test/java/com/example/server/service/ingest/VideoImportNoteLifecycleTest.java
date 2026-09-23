package com.example.server.service.ingest;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.service.MediaIndexService;
import com.example.server.service.MediaService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 默认笔记生命周期 → 导入状态的映射契约（D-047 / D-066）。
 *
 * <p>本类的核心边界是"哪些变化值得写子项"：中间态（{@code ANALYZING}、回到 {@code ANALYSIS_QUEUED}）
 * 只写媒体行，只有终态（{@code READY/FAILED}）才同步子项并重算父任务——父任务的重算会连带写入父表并触发
 * 终态通知，多写一次就是多一次无意义的聚合。
 *
 * <p>另一个边界是身份：只有默认目标 {@code VIDEO_NOTE_V1} 的事件能影响导入状态，用户自定义目标不得触碰。
 */
class VideoImportNoteLifecycleTest {

    private static final Long MEDIA_ID = 22L;
    private static final String GOAL = VideoNoteProfile.GOAL;
    private static final AnalysisMode MODE = VideoNoteProfile.MODE;

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final VideoImportItemMapper itemMapper = mock(VideoImportItemMapper.class);
    private final ImportJobAggregator aggregator = mock(ImportJobAggregator.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final MediaIndexService mediaIndexService = mock(MediaIndexService.class);

    private final VideoImportNoteLifecycle lifecycle =
            new VideoImportNoteLifecycle(mediaFileMapper, itemMapper, aggregator, mediaService,
                    mediaIndexService);

    @org.junit.jupiter.api.BeforeEach
    void indexIsReadyByDefault() {
        // 索引就绪是完成边界的一部分（D-069），默认视为已就绪；单独的不就绪场景由专门用例覆盖。
        when(mediaIndexService.ensureIndexed(anyLong())).thenReturn(true);
    }

    /** 开始分析是中间态：只推进媒体，不写子项、不聚合。 */
    @Test
    void onStartedOnlyAdvancesMedia() {
        when(mediaFileMapper.casStatus(MEDIA_ID,
                MediaImportStatus.ANALYSIS_QUEUED, MediaImportStatus.ANALYZING)).thenReturn(1);

        lifecycle.onStarted(MEDIA_ID, GOAL, MODE);

        verify(mediaFileMapper).casStatus(MEDIA_ID,
                MediaImportStatus.ANALYSIS_QUEUED, MediaImportStatus.ANALYZING);
        verifyNoInteractions(itemMapper, aggregator, mediaService);
    }

    /** 可重试失败回到 ANALYSIS_QUEUED 同样是中间态：子项不跟随。 */
    @Test
    void onRetryableFailureOnlyAdvancesMedia() {
        when(mediaFileMapper.casStatus(MEDIA_ID,
                MediaImportStatus.ANALYZING, MediaImportStatus.ANALYSIS_QUEUED)).thenReturn(1);

        lifecycle.onRetryableFailure(MEDIA_ID, GOAL, MODE);

        verify(mediaFileMapper).markNoteDeferred(MEDIA_ID, MediaImportStatus.ANALYSIS_QUEUED,
                VideoNoteProfile.VERSION, VideoImportErrorCode.NOTE_PROCESSING_FAILED.name(),
                VideoImportErrorCode.NOTE_PROCESSING_FAILED.message());
        verifyNoInteractions(itemMapper, aggregator, mediaService);
    }

    /** 完成是终态：子项 READY + 重算父任务（父任务在此刻进入 COMPLETED 并发终态通知）。 */
    @Test
    void onCompletedSyncsTerminalItemAndAggregates() {
        when(mediaFileMapper.casStatus(MEDIA_ID,
                MediaImportStatus.ANALYZING, MediaImportStatus.READY)).thenReturn(1);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media());

        lifecycle.onCompleted(MEDIA_ID, GOAL, MODE, true);

        verify(mediaFileMapper).markNoteCompleted(MEDIA_ID,
                MediaImportStatus.READY, MediaImportStatus.READY, VideoNoteProfile.VERSION);
        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.READY, false, null);
        verify(aggregator).recomputeForMedia(MEDIA_ID);
        verify(mediaService).invalidateUserList(7L);
    }

    /**
     * D-104：Critic 两轮仍未通过时仍要进 READY（可用性优先），但不得走清空错误痕迹的
     * {@code markNoteCompleted}——必须走 {@code markNoteCompletedWithWarning} 留下可复核标记，
     * 否则就是 media 66 那种"Critic 判失败、用户却看不到任何提示"的回归。
     */
    @Test
    void onCompletedWithUnverifiedCritiqueMarksWarningInsteadOfClearingErrorCode() {
        when(mediaFileMapper.casStatus(MEDIA_ID,
                MediaImportStatus.ANALYZING, MediaImportStatus.READY)).thenReturn(1);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media());

        lifecycle.onCompleted(MEDIA_ID, GOAL, MODE, false);

        verify(mediaFileMapper).markNoteCompletedWithWarning(MEDIA_ID,
                MediaImportStatus.READY, MediaImportStatus.READY, VideoNoteProfile.VERSION,
                VideoImportErrorCode.NOTE_UNVERIFIED_EVIDENCE.name(),
                VideoImportErrorCode.NOTE_UNVERIFIED_EVIDENCE.message());
        verify(mediaFileMapper, never()).markNoteCompleted(anyLong(), any(), any(), anyString());
        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.READY, false, null);
        verify(aggregator).recomputeForMedia(MEDIA_ID);
    }

    /** 复用已有结果的路径可能没有"开始处理"：从 ANALYSIS_QUEUED 直接完成必须被接受。 */
    @Test
    void onCompletedAcceptsDirectTransitionFromQueued() {
        when(mediaFileMapper.casStatus(MEDIA_ID,
                MediaImportStatus.ANALYZING, MediaImportStatus.READY)).thenReturn(0);
        when(mediaFileMapper.casStatus(MEDIA_ID,
                MediaImportStatus.ANALYSIS_QUEUED, MediaImportStatus.READY)).thenReturn(1);

        lifecycle.onCompleted(MEDIA_ID, GOAL, MODE, true);

        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.READY, false, null);
    }

    /** 状态已经被别的路径推进时不得再写子项：否则会把已经收敛的子项改回去（重复消息）。 */
    @Test
    void onCompletedWithoutTransitionTouchesNothing() {
        when(mediaFileMapper.casStatus(anyLong(), any(), any())).thenReturn(0);

        lifecycle.onCompleted(MEDIA_ID, GOAL, MODE, true);

        verifyNoInteractions(itemMapper, aggregator, mediaService);
    }

    @Test
    void onPermanentFailureSyncsTerminalItemAndAggregates() {
        when(mediaFileMapper.casStatus(MEDIA_ID,
                MediaImportStatus.ANALYZING, MediaImportStatus.FAILED)).thenReturn(1);

        lifecycle.onPermanentFailure(MEDIA_ID, GOAL, MODE);

        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.FAILED, false,
                VideoImportErrorCode.NOTE_PROCESSING_REJECTED.name());
        verify(aggregator).recomputeForMedia(MEDIA_ID);
    }

    /** 用户自定义目标（或不同模式）的事件完全不影响导入状态。 */
    @Test
    void otherGoalsAreIgnored() {
        lifecycle.onStarted(MEDIA_ID, "帮我总结这段视频的论点", AnalysisMode.GENERAL);
        lifecycle.onCompleted(MEDIA_ID, "帮我总结这段视频的论点", AnalysisMode.GENERAL, true);
        lifecycle.onRetryableFailure(MEDIA_ID, "帮我总结这段视频的论点", AnalysisMode.GENERAL);
        lifecycle.onPermanentFailure(MEDIA_ID, "帮我总结这段视频的论点", AnalysisMode.GENERAL);

        verify(mediaFileMapper, never()).casStatus(anyLong(), any(), any());
        verifyNoInteractions(itemMapper, aggregator, mediaService);
    }

    /** 子项同步失败不能冒泡：AI 主链已经完成，子项落库由恢复扫描兜底。 */
    @Test
    void itemSyncFailureDoesNotBreakNoteCompletion() {
        when(mediaFileMapper.casStatus(MEDIA_ID,
                MediaImportStatus.ANALYZING, MediaImportStatus.READY)).thenReturn(1);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media());
        when(itemMapper.updatePendingToTerminalByMediaId(eq(MEDIA_ID), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("db down"));

        lifecycle.onCompleted(MEDIA_ID, GOAL, MODE, true);

        verify(mediaService).invalidateUserList(7L);
    }

    /** 空白目标按"非默认"处理，不能因为摘要计算而抛错。 */
    @Test
    void blankGoalIsIgnoredWithoutComputeError() {
        lifecycle.onCompleted(MEDIA_ID, "  ", MODE, true);

        verify(mediaFileMapper, never()).casStatus(anyLong(), any(), any());
        verify(itemMapper, never()).updatePendingToTerminalByMediaId(anyLong(), any(), anyBoolean(), anyString());
    }

    /**
     * AC-10：笔记已经写好但检索索引还没就绪时，不得进入 {@code READY}、不得同步子项、不得聚合父任务。
     *
     * <p>这是"完成"的定义问题：用户收到完成通知就会立刻提问，此时检索不到证据等于功能不可用。
     * 媒体保持 `ANALYZING`，由恢复扫描在索引补出来之后再推进。
     */
    @Test
    void completedNoteWithoutReadyIndexStaysProcessing() {
        when(mediaIndexService.ensureIndexed(MEDIA_ID)).thenReturn(false);

        lifecycle.onCompleted(MEDIA_ID, GOAL, MODE, true);

        verify(mediaFileMapper, never()).casStatus(anyLong(), any(), any());
        verify(mediaFileMapper, never()).markNoteCompleted(anyLong(), any(), any(), anyString());
        verifyNoInteractions(itemMapper, aggregator, mediaService);
    }

    /** 索引检查本身不能因为向量库或检查点异常而把完成路径打挂。 */
    @Test
    void indexCheckFailureIsTreatedAsNotReady() {
        when(mediaIndexService.ensureIndexed(MEDIA_ID)).thenReturn(false);

        lifecycle.onCompleted(MEDIA_ID, GOAL, MODE, true);

        verify(mediaFileMapper, never()).markNoteCompleted(anyLong(), any(), any(), anyString());
    }

    private MediaFile media() {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(7L);
        return media;
    }
}
