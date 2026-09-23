package com.example.server.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机契约：只允许契约声明的推进方向，禁止回退和混用两套枚举。
 *
 * <p>父任务状态回答“这次 URL 提交整体怎么样”，媒体状态回答“这个可播放单元处理到哪一步”。
 * 两者都不是可自由赋值的字符串。
 */
class ImportStatusTransitionTest {

    @Test
    void mediaChainOnlyAllowsDocumentedForwardSteps() {
        assertTrue(MediaImportStatus.PENDING_DISPATCH.canTransitionTo(MediaImportStatus.QUEUED));
        assertTrue(MediaImportStatus.QUEUED.canTransitionTo(MediaImportStatus.ACQUIRING));
        assertTrue(MediaImportStatus.ACQUIRING.canTransitionTo(MediaImportStatus.MEDIA_READY));
        assertTrue(MediaImportStatus.MEDIA_READY.canTransitionTo(MediaImportStatus.ANALYSIS_QUEUED));
        assertTrue(MediaImportStatus.ANALYSIS_QUEUED.canTransitionTo(MediaImportStatus.ANALYZING));
        assertTrue(MediaImportStatus.ANALYZING.canTransitionTo(MediaImportStatus.READY));
    }

    @Test
    void mediaNeverFallsBackFromStoredOrTerminalStates() {
        assertFalse(MediaImportStatus.MEDIA_READY.canTransitionTo(MediaImportStatus.ACQUIRING));
        assertFalse(MediaImportStatus.READY.canTransitionTo(MediaImportStatus.ANALYZING));
        assertFalse(MediaImportStatus.COMPLETED.canTransitionTo(MediaImportStatus.READY));
        assertFalse(MediaImportStatus.FAILED.canTransitionTo(MediaImportStatus.ACQUIRING));
        assertFalse(MediaImportStatus.QUEUED.canTransitionTo(MediaImportStatus.MEDIA_READY));
    }

    @Test
    void retryableMediaFailuresReturnToDocumentedRetryEntryOnly() {
        assertTrue(MediaImportStatus.ACQUIRING.canTransitionTo(MediaImportStatus.QUEUED));
        assertTrue(MediaImportStatus.ANALYZING.canTransitionTo(MediaImportStatus.ANALYSIS_QUEUED));
        assertTrue(MediaImportStatus.DISPATCH_FAILED.canTransitionTo(MediaImportStatus.QUEUED));
        assertTrue(MediaImportStatus.FAILED.canTransitionTo(MediaImportStatus.PENDING_DISPATCH));
        assertFalse(MediaImportStatus.MEDIA_READY.canTransitionTo(MediaImportStatus.QUEUED));
        assertFalse(MediaImportStatus.ANALYSIS_QUEUED.canTransitionTo(MediaImportStatus.QUEUED));
    }

    @Test
    void mediaStoredFlagStopsSecondDownload() {
        assertFalse(MediaImportStatus.ACQUIRING.isMediaStored());
        assertTrue(MediaImportStatus.MEDIA_READY.isMediaStored());
        assertTrue(MediaImportStatus.READY.isMediaStored());
        assertTrue(MediaImportStatus.COMPLETED.isMediaStored());
        assertTrue(MediaImportStatus.DISPATCH_FAILED.needsAcquire());
        assertFalse(MediaImportStatus.MEDIA_READY.needsAcquire());
    }

    @Test
    void onlyReadyAndLegacyCompletedAreVisibleInMediaList() {
        assertTrue(MediaImportStatus.READY.isVisibleInMediaList());
        assertTrue(MediaImportStatus.COMPLETED.isVisibleInMediaList());
        for (MediaImportStatus status : List.of(MediaImportStatus.PENDING_DISPATCH, MediaImportStatus.QUEUED,
                MediaImportStatus.ACQUIRING, MediaImportStatus.MEDIA_READY, MediaImportStatus.ANALYSIS_QUEUED,
                MediaImportStatus.ANALYZING, MediaImportStatus.DISPATCH_FAILED, MediaImportStatus.FAILED)) {
            assertFalse(status.isVisibleInMediaList(), status.name() + " 不应出现在媒体列表");
        }
    }

    @Test
    void jobTerminalStatesAndRetryEntriesFollowContract() {
        assertTrue(VideoImportJobStatus.COMPLETED.isTerminal());
        assertTrue(VideoImportJobStatus.PARTIAL_SUCCESS.isTerminal());
        assertTrue(VideoImportJobStatus.FAILED.isTerminal());
        assertFalse(VideoImportJobStatus.DISPATCH_FAILED.isTerminal());
        assertTrue(VideoImportJobStatus.FAILED.isRetryEntry());
        assertTrue(VideoImportJobStatus.DISPATCH_FAILED.isRetryEntry());
    }

    @Test
    void jobNeverReturnsToEarlierStages() {
        assertTrue(VideoImportJobStatus.PENDING_DISPATCH.canTransitionTo(VideoImportJobStatus.QUEUED));
        assertTrue(VideoImportJobStatus.PENDING_DISPATCH.canTransitionTo(VideoImportJobStatus.DISPATCH_FAILED));
        assertTrue(VideoImportJobStatus.QUEUED.canTransitionTo(VideoImportJobStatus.RESOLVING));
        assertTrue(VideoImportJobStatus.RESOLVING.canTransitionTo(VideoImportJobStatus.PROCESSING));
        assertTrue(VideoImportJobStatus.PROCESSING.canTransitionTo(VideoImportJobStatus.COMPLETED));
        assertTrue(VideoImportJobStatus.FAILED.canTransitionTo(VideoImportJobStatus.PENDING_DISPATCH));
        assertTrue(VideoImportJobStatus.PARTIAL_SUCCESS.canTransitionTo(VideoImportJobStatus.PROCESSING));
        assertFalse(VideoImportJobStatus.PROCESSING.canTransitionTo(VideoImportJobStatus.RESOLVING));
        assertFalse(VideoImportJobStatus.COMPLETED.canTransitionTo(VideoImportJobStatus.PROCESSING));
        assertFalse(VideoImportJobStatus.RESOLVING.canTransitionTo(VideoImportJobStatus.QUEUED));
    }
}
