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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重试接口契约（§3.4）。
 *
 * <p>关键边界：只有三类状态可重试；不可重试失败返回 409；同一 URL 已有其他活跃任务时返回那个任务；
 * {@code PARTIAL_SUCCESS} 只重投失败单元，已入库单元不重新下载。
 */
class ImportJobRetryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long IMPORT_ID = 10L;
    private static final String REQUEST_HASH = "hash-value";
    private static final String ACTIVE_KEY = VideoImportKeys.activeRequestKey(USER_ID, REQUEST_HASH);

    private final VideoImportJobMapper jobMapper = mock(VideoImportJobMapper.class);
    private final VideoImportItemMapper itemMapper = mock(VideoImportItemMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final ImportUnitDispatcher unitDispatcher = mock(ImportUnitDispatcher.class);
    private final ImportJobAggregator aggregator = mock(ImportJobAggregator.class);
    private final ImportResolveDispatcher resolveDispatcher = mock(ImportResolveDispatcher.class);

    private final ImportJobRetryService service = new ImportJobRetryService(
            jobMapper, itemMapper, mediaFileMapper, unitDispatcher, aggregator, resolveDispatcher);

    @Test
    void dispatchFailedJobIsRestartedFromResolveWithSameImportId() {
        VideoImportJob job = job(VideoImportJobStatus.DISPATCH_FAILED);
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job);
        when(jobMapper.findByActiveRequestKey(ACTIVE_KEY)).thenReturn(null);
        when(jobMapper.casStatusAndAttachActiveKey(IMPORT_ID, VideoImportJobStatus.DISPATCH_FAILED,
                VideoImportJobStatus.PENDING_DISPATCH, ACTIVE_KEY)).thenReturn(1);
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job);

        VideoImportSubmissionResponse response = service.retry(USER_ID, IMPORT_ID);

        assertFalse(response.reused());
        assertEquals(IMPORT_ID, response.importId());
        verify(resolveDispatcher).dispatch(any(VideoImportJob.class));
        // 用户显式重试重置自动恢复预算，否则恢复扫描会在下一轮立刻把任务再判失败（D-053）。
        verify(jobMapper).resetAttempt(IMPORT_ID);
    }

    @Test
    void nonRetryableFailedJobReturns409() {
        VideoImportJob job = job(VideoImportJobStatus.FAILED);
        job.setRetryable(false);
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job);

        BusinessException error = assertThrows(BusinessException.class, () -> service.retry(USER_ID, IMPORT_ID));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
    }

    @Test
    void inFlightJobCannotBeRetried() {
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job(VideoImportJobStatus.PROCESSING));

        BusinessException error = assertThrows(BusinessException.class, () -> service.retry(USER_ID, IMPORT_ID));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
    }

    @Test
    void otherActiveJobForSameUrlIsReusedInsteadOfCreatingSecondResolve() {
        VideoImportJob job = job(VideoImportJobStatus.DISPATCH_FAILED);
        VideoImportJob other = job(VideoImportJobStatus.RESOLVING);
        other.setId(99L);
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job);
        when(jobMapper.findByActiveRequestKey(ACTIVE_KEY)).thenReturn(other);

        VideoImportSubmissionResponse response = service.retry(USER_ID, IMPORT_ID);

        assertTrue(response.reused());
        assertEquals(99L, response.importId());
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
    }

    @Test
    void partialSuccessOnlyRedispatchesRetryableFailedUnits() {
        VideoImportJob job = job(VideoImportJobStatus.PARTIAL_SUCCESS);
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job);
        when(jobMapper.casStatus(IMPORT_ID, VideoImportJobStatus.PARTIAL_SUCCESS,
                VideoImportJobStatus.PROCESSING)).thenReturn(1);
        when(itemMapper.findByImportId(IMPORT_ID)).thenReturn(List.of(
                item(21L, MediaImportStatus.READY, false),
                item(22L, MediaImportStatus.FAILED, true),
                item(23L, MediaImportStatus.FAILED, false)));
        when(mediaFileMapper.selectById(22L)).thenReturn(media(22L, MediaImportStatus.FAILED));
        when(mediaFileMapper.casStatus(22L, MediaImportStatus.FAILED, MediaImportStatus.PENDING_DISPATCH))
                .thenReturn(1);
        when(itemMapper.casItemStatus(IMPORT_ID, 22L, MediaImportStatus.FAILED,
                MediaImportStatus.PENDING_DISPATCH, true, null)).thenReturn(1);
        when(unitDispatcher.dispatch(IMPORT_ID, 22L, "trace-10")).thenReturn(true);

        VideoImportSubmissionResponse response = service.retry(USER_ID, IMPORT_ID);

        assertFalse(response.reused());
        verify(unitDispatcher).dispatch(IMPORT_ID, 22L, "trace-10");
        // 已入库的 21 与不可重试的 23 都不重投。
        verify(unitDispatcher, never()).dispatch(anyLong(), eq(21L), anyString());
        verify(unitDispatcher, never()).dispatch(anyLong(), eq(23L), anyString());
        verify(jobMapper).incrementAttempt(IMPORT_ID);
        // 重试的单元同时重置自动重投预算，让恢复扫描按新的尝试次数判断。
        verify(mediaFileMapper).resetAcquireAttempt(22L);
    }

    @Test
    void partialSuccessWithoutRetryableUnitsRecomputesParentOnly() {
        VideoImportJob job = job(VideoImportJobStatus.PARTIAL_SUCCESS);
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job);
        when(jobMapper.casStatus(IMPORT_ID, VideoImportJobStatus.PARTIAL_SUCCESS,
                VideoImportJobStatus.PROCESSING)).thenReturn(1);
        when(itemMapper.findByImportId(IMPORT_ID)).thenReturn(List.of(
                item(23L, MediaImportStatus.FAILED, false)));

        service.retry(USER_ID, IMPORT_ID);

        verify(unitDispatcher, never()).dispatch(anyLong(), anyLong(), anyString());
        verify(aggregator).recompute(IMPORT_ID);
    }

    @Test
    void missingOrForeignJobReturns404() {
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(null);

        assertThrows(NoSuchElementException.class, () -> service.retry(USER_ID, IMPORT_ID));
    }

    @Test
    void stateChangedConcurrentlyReturns409() {
        VideoImportJob job = job(VideoImportJobStatus.FAILED);
        job.setRetryable(true);
        when(jobMapper.findOwnedById(IMPORT_ID, USER_ID)).thenReturn(job);
        when(jobMapper.findByActiveRequestKey(ACTIVE_KEY)).thenReturn(null);
        when(jobMapper.casStatusAndAttachActiveKey(anyLong(), any(), any(), anyString())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.retry(USER_ID, IMPORT_ID));

        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(resolveDispatcher, never()).dispatch(any(VideoImportJob.class));
    }

    private VideoImportJob job(VideoImportJobStatus status) {
        VideoImportJob job = new VideoImportJob();
        job.setId(IMPORT_ID);
        job.setUserId(USER_ID);
        job.setStatus(status);
        job.setRequestHash(REQUEST_HASH);
        job.setTraceId("trace-10");
        job.setRetryable(true);
        return job;
    }

    private VideoImportItem item(Long mediaId, MediaImportStatus status, boolean retryable) {
        VideoImportItem item = new VideoImportItem();
        item.setImportId(IMPORT_ID);
        item.setMediaId(mediaId);
        item.setItemStatus(status);
        item.setRetryable(retryable);
        return item;
    }

    private MediaFile media(Long mediaId, MediaImportStatus status) {
        MediaFile media = new MediaFile();
        media.setId(mediaId);
        media.setStatus(status);
        return media;
    }
}
