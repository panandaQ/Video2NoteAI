package com.example.server.service.ingest;

import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.entity.VideoImportItem;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.service.TaskEventService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 父任务聚合契约：计数与状态只能从子项推导。
 *
 * <p>关键约束：默认笔记全部完成前父任务不得进入 {@code COMPLETED}；终态必须清空活跃键并失效请求缓存，
 * 否则相同 URL 无法再次提交。
 */
class ImportJobAggregatorTest {

    private static final Long IMPORT_ID = 10L;

    private final VideoImportJobMapper jobMapper = mock(VideoImportJobMapper.class);
    private final VideoImportItemMapper itemMapper = mock(VideoImportItemMapper.class);
    private final ImportRequestCache requestCache = mock(ImportRequestCache.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);

    /** 用真实发布器包一层 mock：既能验证"发不发"，也能验证事件内容（D-066）。 */
    private final ImportTerminalEventPublisher terminalPublisher =
            new ImportTerminalEventPublisher(taskEventService);

    private final ImportJobAggregator aggregator =
            new ImportJobAggregator(jobMapper, itemMapper, requestCache, terminalPublisher);

    @Test
    void allNotesCompletedMovesJobToCompletedAndReleasesActiveKey() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job(VideoImportJobStatus.PROCESSING));
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(
                count(MediaImportStatus.READY, 1, 1), count(MediaImportStatus.READY, 1, 0)));
        when(jobMapper.casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.PROCESSING, VideoImportJobStatus.COMPLETED)).thenReturn(1);

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper).updateCounts(IMPORT_ID, 2, 1, 2, 0);
        verify(jobMapper).casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.PROCESSING, VideoImportJobStatus.COMPLETED);
        verify(requestCache).forget(7L, "hash");
        verify(taskEventService).publishVideoImport(IMPORT_ID,
                TaskStatus.of(TaskStatus.State.COMPLETED, ImportTerminalEventPublisher.COMPLETED_MESSAGE));
    }

    /** 并发/重复消息里 CAS 失败的一侧不能再发一次终态，否则客户端会收到重复通知（D-066）。 */
    @Test
    void terminalCasLosingSideDoesNotPublishAgain() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job(VideoImportJobStatus.PROCESSING));
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(count(MediaImportStatus.READY, 1, 0)));

        aggregator.recompute(IMPORT_ID);

        verify(taskEventService, never()).publishVideoImport(anyLong(), any());
    }

    @Test
    void inFlightItemsKeepJobProcessingAndDoNotReleaseActiveKey() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job(VideoImportJobStatus.RESOLVING));
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(
                count(MediaImportStatus.READY, 1, 0), count(MediaImportStatus.ANALYZING, 1, 0)));

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper).updateCounts(IMPORT_ID, 2, 0, 1, 0);
        verify(jobMapper).casStatus(IMPORT_ID, VideoImportJobStatus.RESOLVING, VideoImportJobStatus.PROCESSING);
        verify(jobMapper, never()).casStatusAndReleaseActiveKey(anyLong(), any(), any());
    }

    @Test
    void allFailedItemsMoveJobToFailed() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job(VideoImportJobStatus.PROCESSING));
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(count(MediaImportStatus.FAILED, 1, 0)));
        when(jobMapper.casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.PROCESSING, VideoImportJobStatus.FAILED)).thenReturn(1);

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper).updateCounts(IMPORT_ID, 1, 0, 0, 1);
        verify(jobMapper).casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.PROCESSING, VideoImportJobStatus.FAILED);
        verify(taskEventService).publishVideoImport(IMPORT_ID,
                TaskStatus.of(TaskStatus.State.FAILED, ImportTerminalEventPublisher.FAILED_MESSAGE));
    }

    @Test
    void mixedTerminalItemsWithFailuresBecomePartialSuccess() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job(VideoImportJobStatus.PROCESSING));
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(
                count(MediaImportStatus.READY, 1, 0), count(MediaImportStatus.FAILED, 1, 0)));

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper).casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.PROCESSING, VideoImportJobStatus.PARTIAL_SUCCESS);
    }

    @Test
    void missingJobOrEmptyItemsAreNoOpsSoRepeatedMessagesStayIdempotent() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(null);
        aggregator.recompute(IMPORT_ID);

        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job(VideoImportJobStatus.RESOLVING));
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of());
        aggregator.recompute(IMPORT_ID);

        verify(jobMapper, never()).updateCounts(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void noTransitionIsWrittenWhenTargetEqualsCurrentStatus() {
        VideoImportJob job = job(VideoImportJobStatus.PROCESSING);
        job.setTotalCount(1);
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job);
        when(itemMapper.countByStatusGrouped(IMPORT_ID))
                .thenReturn(List.of(count(MediaImportStatus.ACQUIRING, 1, 0)));

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper, never()).casStatus(anyLong(), any(), any());
        // 计数与行上现有值一致时连计数都不回写（见下一个用例）。
        verify(jobMapper, never()).updateCounts(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 计数没变就不写。
     *
     * <p>这是写入优化里最关键的一条：父任务每推进一次都会重算，而重复消息、恢复扫描对账、
     * 并发推进都会产生"四个计数完全一样"的重算。跳过回写不仅省一次 UPDATE，还切断了与恢复扫描的
     * 相互触发——写计数会刷新 {@code updated_at}，让没有实质变化的中间态反复被判定为"超时未推进"。
     */
    @Test
    void unchangedCountsAreNotWrittenBack() {
        VideoImportJob job = job(VideoImportJobStatus.PROCESSING);
        job.setTotalCount(4);
        job.setReusedCount(2);
        job.setCompletedCount(1);
        job.setFailedCount(0);
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job);
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(
                count(MediaImportStatus.READY, 1, 1),
                count(MediaImportStatus.ANALYZING, 3, 1)));

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper, never()).updateCounts(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /** 计数发生真实变化时必须回写（否则查询接口会一直显示旧进度）。 */
    @Test
    void changedCountsAreWrittenBack() {
        VideoImportJob job = job(VideoImportJobStatus.PROCESSING);
        job.setTotalCount(4);
        job.setReusedCount(0);
        job.setCompletedCount(0);
        job.setFailedCount(0);
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job);
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(
                count(MediaImportStatus.READY, 2, 0),
                count(MediaImportStatus.ANALYZING, 2, 0)));

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper).updateCounts(IMPORT_ID, 4, 0, 2, 0);
    }

    /**
     * 终态与子项事实矛盾（崩溃或人工 SQL 留下的 FAILED，子项却全部 READY）必须按子项收敛：
     * 查询接口返回 {@code status=FAILED, completedCount=N, failedCount=0} 是用户可见的自相矛盾，
     * 而视频其实已经在库里（D-052）。
     */
    @Test
    void failedJobWithAllItemsReadyIsReconciledToCompleted() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job(VideoImportJobStatus.FAILED));
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(
                count(MediaImportStatus.READY, 1, 1), count(MediaImportStatus.READY, 1, 0)));
        when(jobMapper.casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.FAILED, VideoImportJobStatus.COMPLETED)).thenReturn(1);

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper).casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.FAILED, VideoImportJobStatus.COMPLETED);
        // 收敛后必须清掉失败文案，否则已完成的任务仍带着错误码。
        verify(jobMapper).updateError(IMPORT_ID, null, null, false);
        verify(requestCache).forget(7L, "hash");
    }

    /** 子项还没全部完成时不得放宽状态机：终态不能被"重算"回进行中。 */
    @Test
    void failedJobWithInFlightItemsIsNotReopened() {
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job(VideoImportJobStatus.FAILED));
        when(itemMapper.countByStatusGrouped(IMPORT_ID)).thenReturn(List.of(
                count(MediaImportStatus.READY, 1, 0), count(MediaImportStatus.ANALYZING, 1, 0)));

        aggregator.recompute(IMPORT_ID);

        verify(jobMapper, never()).casStatus(anyLong(), any(), any());
        verify(jobMapper, never()).casStatusAndReleaseActiveKey(anyLong(), any(), any());
    }

    private VideoImportJob job(VideoImportJobStatus status) {
        VideoImportJob job = new VideoImportJob();
        job.setId(IMPORT_ID);
        job.setUserId(7L);
        job.setStatus(status);
        job.setRequestHash("hash");
        job.setTotalCount(0);
        job.setReusedCount(0);
        job.setCompletedCount(0);
        job.setFailedCount(0);
        return job;
    }

    /** 聚合输入现在是一条 GROUP BY 结果：一个状态一行（total=该状态子项数，reused=其中被复用的数）。 */
    private VideoImportItemMapper.ItemStatusCount count(MediaImportStatus status, long total, long reused) {
        return new VideoImportItemMapper.ItemStatusCount(status.name(), total, reused);
    }
}
