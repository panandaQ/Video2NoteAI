package com.example.server.service.ingest;

import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.entity.VideoImportItem;
import com.example.server.mapper.VideoImportItemMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除联动契约（§9.4 / AC-21）：媒体行删除之前，引用它的非终态导入子项必须收敛为
 * {@code FAILED/MEDIA_DELETED} 并从子项重算父任务，否则父任务会永久停在处理中。
 */
class VideoImportDeletionLifecycleTest {

    private static final Long MEDIA_ID = 22L;
    private static final Long USER_ID = 7L;

    private final VideoImportItemMapper itemMapper = mock(VideoImportItemMapper.class);
    private final ImportJobAggregator aggregator = mock(ImportJobAggregator.class);

    private final VideoImportDeletionLifecycle lifecycle =
            new VideoImportDeletionLifecycle(itemMapper, aggregator);

    @Test
    void closesOpenItemsAndRecomputesEveryAffectedJob() {
        when(itemMapper.findByMediaId(MEDIA_ID)).thenReturn(List.of(
                item(10L, MediaImportStatus.ANALYZING),
                item(11L, MediaImportStatus.MEDIA_READY),
                item(10L, MediaImportStatus.READY)));
        when(itemMapper.updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.FAILED, false,
                VideoImportErrorCode.MEDIA_DELETED.name())).thenReturn(1);

        lifecycle.beforeMediaDeleted(MEDIA_ID, USER_ID);

        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.FAILED, false,
                VideoImportErrorCode.MEDIA_DELETED.name());
        // 受影响父任务各自重算一次，且不重复。
        verify(aggregator).recompute(10L);
        verify(aggregator).recompute(11L);
    }

    /** 所有子项都已是终态时不做任何更新，也不需要重算父任务。 */
    @Test
    void doesNothingWhenNoOpenItemsReferenceTheMedia() {
        when(itemMapper.findByMediaId(MEDIA_ID)).thenReturn(List.of(
                item(10L, MediaImportStatus.READY)));
        when(itemMapper.updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.FAILED, false,
                VideoImportErrorCode.MEDIA_DELETED.name())).thenReturn(0);

        lifecycle.beforeMediaDeleted(MEDIA_ID, USER_ID);

        verify(aggregator, never()).recompute(any());
    }

    /** 幂等：媒体重复删除或没有子项时不得抛错。 */
    @Test
    void toleratesMediaWithoutItems() {
        when(itemMapper.findByMediaId(MEDIA_ID)).thenReturn(List.of());
        when(itemMapper.updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.FAILED, false,
                VideoImportErrorCode.MEDIA_DELETED.name())).thenReturn(0);

        lifecycle.beforeMediaDeleted(MEDIA_ID, USER_ID);

        verify(itemMapper, never()).casItemStatus(any(), any(), any(), any(), anyBoolean(), anyString());
    }

    private VideoImportItem item(Long importId, MediaImportStatus status) {
        VideoImportItem item = new VideoImportItem();
        item.setImportId(importId);
        item.setMediaId(MEDIA_ID);
        item.setItemStatus(status);
        return item;
    }
}
