package com.example.server.service.ingest;

import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoImportDetailResponse;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.entity.MediaFile;
import com.example.server.entity.VideoImportItem;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.service.AgentCheckpointService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导入查询契约：归属校验、`stage` 可空投影（D-033）、进度取自媒体行（D-066）。
 *
 * <p>查询不设缓存：客户端主流程改为订阅终态 SSE 后，"高频轮询同一个未推进的任务"这一热点已消失。
 * 因此这里固定的是每次查询都直查数据库，而不是缓存命中行为。
 */
class VideoImportQueryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long IMPORT_ID = 10L;
    private static final Long MEDIA_ID = 21L;

    private final VideoImportJobMapper jobMapper = mock(VideoImportJobMapper.class);
    private final VideoImportItemMapper itemMapper = mock(VideoImportItemMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);

    private final VideoImportQueryService service = new VideoImportQueryService(
            jobMapper, itemMapper, mediaFileMapper, checkpointService);

    @Test
    void missingOrForeignJobIsReportedAsNotFound() {
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(null);

        assertThrows(NoSuchElementException.class, () -> service.detail(USER_ID, IMPORT_ID));
    }

    /** 终态回放（D-066）：对外只有 PROCESSING/COMPLETED/FAILED 三种语义，不泄漏细粒度阶段。 */
    @Test
    void terminalEventProjectionCoversAllJobStates() {
        assertEquals(TaskStatus.State.COMPLETED, event(VideoImportJobStatus.COMPLETED).state());
        assertEquals(ImportTerminalEventPublisher.COMPLETED_MESSAGE,
                event(VideoImportJobStatus.COMPLETED).message());

        assertEquals(TaskStatus.State.FAILED, event(VideoImportJobStatus.FAILED).state());
        assertEquals(ImportTerminalEventPublisher.FAILED_MESSAGE,
                event(VideoImportJobStatus.FAILED).message());

        // 部分成功也是终态：多单元部分失败对客户端就是"有失败"，但文案更准确。
        assertEquals(TaskStatus.State.FAILED, event(VideoImportJobStatus.PARTIAL_SUCCESS).state());
        assertEquals(ImportTerminalEventPublisher.PARTIAL_MESSAGE,
                event(VideoImportJobStatus.PARTIAL_SUCCESS).message());

        for (VideoImportJobStatus status : List.of(VideoImportJobStatus.PENDING_DISPATCH,
                VideoImportJobStatus.QUEUED, VideoImportJobStatus.RESOLVING,
                VideoImportJobStatus.PROCESSING, VideoImportJobStatus.DISPATCH_FAILED)) {
            assertEquals(TaskStatus.State.PROCESSING, event(status).state(), status.name());
        }
    }

    /** 终态事件不携带阶段与结果：结果由媒体列表和详情接口提供，事件只负责"叫醒"。 */
    @Test
    void terminalEventCarriesNoStageOrResult() {
        TaskEvent event = event(VideoImportJobStatus.COMPLETED);

        assertNull(event.stage());
        assertNull(event.result());
        assertTrue(event.terminal());
    }

    @Test
    void terminalEventForForeignJobIsRejected() {
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(null);

        assertThrows(NoSuchElementException.class, () -> service.currentEvent(USER_ID, IMPORT_ID));
    }

    private TaskEvent event(VideoImportJobStatus status) {
        VideoImportJob job = job(status);
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job);
        return service.currentEvent(USER_ID, IMPORT_ID);
    }

    /** 分析开始前不得伪造阶段：`PENDING_DISPATCH` 的 `stage` 必须是 null。 */
    /**
     * 分析开始前不得伪造阶段：`stage` 必须是 null。
     *
     * <p>D-066 之后进度投影来自<b>媒体行</b>（子项只持久化 PENDING_* → READY/FAILED），
     * 因此子项停在 PENDING_DISPATCH 而媒体已经 MEDIA_READY 时，对外状态取媒体的值。
     */
    @Test
    void stageIsNullBeforeAnalysisStarts() {
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job(VideoImportJobStatus.PROCESSING));
        when(itemMapper.findByImportId(IMPORT_ID))
                .thenReturn(List.of(item(MediaImportStatus.PENDING_DISPATCH)));
        when(mediaFileMapper.selectBatchIds(any())).thenReturn(List.of(media(MediaImportStatus.MEDIA_READY)));

        VideoImportDetailResponse response = service.detail(USER_ID, IMPORT_ID);

        assertEquals(1, response.items().size());
        assertNull(response.items().get(0).stage());
        assertEquals(MediaImportStatus.MEDIA_READY, response.items().get(0).status());
    }

    /** `READY` 直接投影为 `COMPLETED`，不读 Checkpoint。 */
    @Test
    void readyItemProjectsCompletedStage() {
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job(VideoImportJobStatus.COMPLETED));
        when(itemMapper.findByImportId(IMPORT_ID)).thenReturn(List.of(item(MediaImportStatus.READY)));
        when(mediaFileMapper.selectBatchIds(any())).thenReturn(List.of(media()));

        VideoImportDetailResponse response = service.detail(USER_ID, IMPORT_ID);

        assertEquals(TaskStage.COMPLETED, response.items().get(0).stage());
        verify(checkpointService, never()).loadStage(anyLong(), anyString(), any());
    }

    /**
     * 查询不设缓存（D-066）：每次都必须重新读库。
     *
     * <p>进度缓存的价值只存在于高频轮询同一任务；客户端改为订阅终态 SSE 后该模式不再存在，
     * 因此这里固定"没有缓存层"这一事实——子项查询次数与查询次数相等。
     */
    @Test
    void everyQueryReadsDatabaseWithoutCache() {
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job(VideoImportJobStatus.PROCESSING));
        when(itemMapper.findByImportId(IMPORT_ID)).thenReturn(List.of(item(MediaImportStatus.ANALYZING)));
        when(mediaFileMapper.selectBatchIds(any())).thenReturn(List.of(media(MediaImportStatus.ANALYZING)));
        when(checkpointService.loadStage(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(TaskStage.CONSUMING);

        VideoImportDetailResponse first = service.detail(USER_ID, IMPORT_ID);
        VideoImportDetailResponse second = service.detail(USER_ID, IMPORT_ID);

        assertEquals(first.importId(), second.importId());
        assertEquals(TaskStage.CONSUMING, second.items().get(0).stage());
        verify(itemMapper, times(2)).findByImportId(IMPORT_ID);
        // 归属校验同样每次都做：没有"缓存绕过鉴权"的路径。
        verify(jobMapper, times(2)).findOwnedById(IMPORT_ID, USER_ID);
    }

    private VideoImportJob job(VideoImportJobStatus status) {
        VideoImportJob job = new VideoImportJob();
        job.setId(IMPORT_ID);
        job.setUserId(USER_ID);
        job.setStatus(status);
        job.setRequestHash("hash");
        job.setTargetType(com.example.server.source.ImportTargetType.SINGLE);
        job.setTotalCount(1);
        job.setReusedCount(0);
        job.setCompletedCount(0);
        job.setFailedCount(0);
        job.setAttemptCount(0);
        job.setRetryable(false);
        job.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        job.setUpdatedAt(LocalDateTime.now());
        return job;
    }

    private VideoImportItem item(MediaImportStatus status) {
        VideoImportItem item = new VideoImportItem();
        item.setImportId(IMPORT_ID);
        item.setMediaId(MEDIA_ID);
        item.setItemOrder(1);
        item.setItemStatus(status);
        item.setReused(false);
        item.setRetryable(false);
        return item;
    }

    private MediaFile media() {
        return media(MediaImportStatus.READY);
    }

    private MediaFile media(MediaImportStatus status) {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(USER_ID);
        media.setStatus(status);
        media.setSourceTitle("标题");
        media.setCanonicalUrl("https://www.bilibili.com/video/BV1xx411c7mD");
        return media;
    }
}
