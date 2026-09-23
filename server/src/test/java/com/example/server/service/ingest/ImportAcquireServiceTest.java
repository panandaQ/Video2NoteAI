package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.entity.ContentAsset;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.BilibiliCredentialService;
import com.example.server.source.VideoSourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit 获取编排契约。
 *
 * <p>最关键的一条是投递竞态：投递方“先发消息、后 CAS 到 QUEUED”，消费者可能在状态推进之前就拿到消息，
 * 此时用过期的读值做 CAS 必然失败。若把它当成“已被占用”而直接返回，消息会被确认且单元永久停在
 * QUEUED——这正是多分 P 首次验收时四个单元全部卡住的真实原因，本测试固定住修复后的行为。
 */
class ImportAcquireServiceTest {

    private static final Long MEDIA_ID = 3L;
    private static final String COVER_URL = "http://localhost:9000/media/video-import/1/3/cover.jpg";

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final VideoImportItemMapper itemMapper = mock(VideoImportItemMapper.class);
    private final ImportMediaAcquirer acquirer = mock(ImportMediaAcquirer.class);
    private final VideoNoteTaskPort noteTaskPort = mock(VideoNoteTaskPort.class);
    private final ImportJobAggregator aggregator = mock(ImportJobAggregator.class);
    private final ImportUnitLock unitLock = mock(ImportUnitLock.class);
    private final ImportUnitDispatcher unitDispatcher = mock(ImportUnitDispatcher.class);
    private final VideoImportProperties properties = new VideoImportProperties();
    private final ContentAssetService contentAssetService = mock(ContentAssetService.class);
    private final ContentArtifactEnrichmentService artifactEnrichment =
            mock(ContentArtifactEnrichmentService.class);
    private final BilibiliCredentialService credentialService = mock(BilibiliCredentialService.class);

    private final ImportAcquireService service = new ImportAcquireService(
            mediaFileMapper, itemMapper, acquirer, noteTaskPort, aggregator, unitLock,
            unitDispatcher, properties, contentAssetService, artifactEnrichment, credentialService);

    /**
     * 跨用户共享字节命中时**不下载**，只把对象引用挂上来并推进状态（D-068 / AC-06）。
     *
     * <p>这是规格故事二的核心：别的用户已经下过这个视频，本次导入只建立自己的内容库条目。
     */
    @Test
    void sharedBytesAreReusedWithoutDownloading() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.QUEUED));
        when(contentAssetService.findByMedia(any(MediaFile.class))).thenReturn(mock(ContentAsset.class));
        when(contentAssetService.adoptSharedBytes(any(MediaFile.class), any(ContentAsset.class))).thenReturn(true);
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.MEDIA_READY))
                .thenReturn(1);
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        service.acquire(MEDIA_ID);

        verify(acquirer, never()).acquireAndStore(any(MediaFile.class), any(), any());
        verify(mediaFileMapper).casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.MEDIA_READY);
        verify(noteTaskPort).submitDefaultNote(MEDIA_ID);
        // 连执行权都不需要取：没有"获取"这回事，状态从 QUEUED 直接到 MEDIA_READY。
        verify(mediaFileMapper, never()).casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING);
    }

    /**
     * 另一个用户正在下载同一份内容（共享内容锁被持有）时，本单元不得再下载第二次。
     *
     * <p>抢锁后**再查一次**资产：对方可能刚好写完。若确实还没写完就原样返回——媒体停在 ACQUIRING，
     * 而内容锁还在，恢复扫描按"有人在下载"跳过它，不重置也不消耗重投预算（D-061 语义）。
     */
    @Test
    void busyContentLockSkipsDownloadAndWaitsForSharedBytes() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.QUEUED));
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING))
                .thenReturn(1);
        ImportUnitLock.Handle rejected = mock(ImportUnitLock.Handle.class);
        when(rejected.acquired()).thenReturn(false);
        when(unitLock.tryLockContent(any())).thenReturn(rejected);
        when(contentAssetService.adoptSharedBytes(any(MediaFile.class), any())).thenReturn(false);

        service.acquire(MEDIA_ID);

        verify(acquirer, never()).acquireAndStore(any(MediaFile.class), any(), any());
        verify(mediaFileMapper, never()).markMediaReady(anyLong(), any(), any(), anyString(), anyString(),
                anyLong(), anyString());
    }

    /** 抢锁失败但对方刚好写完字节：本次导入直接复用，不下载。 */
    @Test
    void contentLockRaceStillReusesBytesWrittenByTheOtherUser() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.QUEUED));
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING))
                .thenReturn(1);
        ImportUnitLock.Handle rejected = mock(ImportUnitLock.Handle.class);
        when(rejected.acquired()).thenReturn(false);
        when(unitLock.tryLockContent(any())).thenReturn(rejected);
        // 第一次（入口处）还没命中；抢锁失败后重查命中——模拟对方刚好发布字节。
        when(contentAssetService.adoptSharedBytes(any(MediaFile.class), any())).thenReturn(false, true);
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.ACQUIRING, MediaImportStatus.MEDIA_READY))
                .thenReturn(1);
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        service.acquire(MEDIA_ID);

        verify(acquirer, never()).acquireAndStore(any(MediaFile.class), any(), any());
        verify(mediaFileMapper).casStatus(MEDIA_ID, MediaImportStatus.ACQUIRING, MediaImportStatus.MEDIA_READY);
    }

    /** 默认放行：多数用例只关心获取编排，不关心分布式锁。 */
    @BeforeEach
    void grantUnitLock() {
        when(artifactEnrichment.ensureArtifacts(any(MediaFile.class)))
                .thenReturn(ContentArtifactEnrichmentService.EnrichmentResult.READY);
        when(unitLock.tryLockUnit(any(), anyString())).thenAnswer(invocation -> {
            ImportUnitLock.Handle handle = mock(ImportUnitLock.Handle.class);
            when(handle.acquired()).thenReturn(true);
            return handle;
        });
        when(unitLock.tryLockContent(any())).thenAnswer(invocation -> {
            ImportUnitLock.Handle handle = mock(ImportUnitLock.Handle.class);
            when(handle.acquired()).thenReturn(true);
            return handle;
        });
    }

    /**
     * 同一单元已经有进程在下载时，后来的消息必须立刻返回且不产生任何副作用。
     *
     * <p>这把锁存在的唯一理由：下载期间 {@code updated_at} 不变，恢复扫描会把"正在下载"误判成卡死并重投；
     * 锁在，第二个消费者就不能再起一次下载。
     */
    @Test
    void busyUnitLockSkipsDownloadWithoutSideEffects() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.QUEUED));
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING))
                .thenReturn(1);
        ImportUnitLock.Handle rejected = mock(ImportUnitLock.Handle.class);
        when(rejected.acquired()).thenReturn(false);
        when(unitLock.tryLockUnit(any(), anyString())).thenReturn(rejected);

        service.acquire(MEDIA_ID);

        verify(acquirer, never()).acquireAndStore(any(MediaFile.class), any(), any());
        verify(mediaFileMapper, never()).markMediaReady(anyLong(), any(), any(), anyString(), anyString(),
                anyLong(), anyString());
        // 执行权已经从 QUEUED 变成 ACQUIRING：持有者完成后会从这个状态推进，不需要回退。
        verify(mediaFileMapper, never()).casStatus(MEDIA_ID,
                MediaImportStatus.ACQUIRING, MediaImportStatus.QUEUED);
    }

    @Test
    void claimsMediaWhoseStatusWasQueuedAfterTheMessageWasRead() {
        // 消费者读到 PENDING_DISPATCH，投递方随后把它改成 QUEUED：第一个 CAS 必然失败。
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.PENDING_DISPATCH));
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.PENDING_DISPATCH, MediaImportStatus.ACQUIRING))
                .thenReturn(0);
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING))
                .thenReturn(1);
        when(acquirer.acquireAndStore(any(MediaFile.class), any(), any()))
                .thenReturn(new ImportMediaAcquirer.StoredMedia("http://minio/media/o.mp4", "hash", "video/mp4", 10L, COVER_URL));
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        service.acquire(MEDIA_ID);

        verify(acquirer).acquireAndStore(any(MediaFile.class), any(), any());
        verify(mediaFileMapper).markMediaReady(eq(MEDIA_ID), eq(MediaImportStatus.ACQUIRING),
                eq(MediaImportStatus.MEDIA_READY), anyString(), eq("hash"), eq(10L), eq(COVER_URL));
        verify(mediaFileMapper).casStatus(MEDIA_ID, MediaImportStatus.MEDIA_READY, MediaImportStatus.ANALYSIS_QUEUED);
    }

    /**
     * 首次下载完成后，投递笔记必须用**刚落库的媒体行**（D-098）。
     *
     * <p>下载前读到的实体上 {@code contentHash} 还是 null，而分析输入补充（字幕/章节 manifest）
     * 以 contentHash 为第一个守卫；传旧实体等于让整条链路静默退化到 ASR——实测代价是 87 分钟视频
     * 白跑 88 片 ASR，并且永远没有章节。
     */
    @Test
    void noteIsSubmittedWithContentHashWrittenByTheDownload() {
        MediaFile refreshed = media(MediaImportStatus.MEDIA_READY);
        refreshed.setFilePath("http://minio/media/o.mp4");
        refreshed.setContentHash("hash");
        when(mediaFileMapper.selectById(MEDIA_ID))
                .thenReturn(media(MediaImportStatus.QUEUED)) // 入口读：此时还没有哈希
                .thenReturn(refreshed);                      // 落库后重读：哈希已写入
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING))
                .thenReturn(1);
        when(acquirer.acquireAndStore(any(MediaFile.class), any(), any()))
                .thenReturn(new ImportMediaAcquirer.StoredMedia("http://minio/media/o.mp4", "hash", "video/mp4", 10L, COVER_URL));
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        service.acquire(MEDIA_ID);

        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(artifactEnrichment).ensureArtifacts(captor.capture());
        assertEquals("hash", captor.getValue().getContentHash());
        verify(mediaFileMapper).casStatus(MEDIA_ID, MediaImportStatus.MEDIA_READY,
                MediaImportStatus.ANALYSIS_QUEUED);
    }

    /**
     * 再次导入一个已经 READY 的 V2 媒体（重建笔记 / 幂等重投）同样必须先补充分析输入（D-098）。
     *
     * <p>补充闸门早先只在 {@code MEDIA_READY} 这一条路径上生效，而终态重投走的是另一个入参：
     * 于是"再次导入"永远没有 manifest，只会在没有字幕与章节的前提下重跑一遍整段 ASR。
     */
    @Test
    void alreadyReadyMediaStillEnrichesBeforeSubmittingNote() {
        MediaFile media = media(MediaImportStatus.READY);
        media.setContentHash("hash");
        media.setNoteProfileVersion(VideoNoteProfile.VERSION);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        service.acquire(MEDIA_ID);

        verify(artifactEnrichment).ensureArtifacts(media);
        verify(noteTaskPort).submitDefaultNote(MEDIA_ID);
    }

    /** READY 路径补充失败时不得投递笔记：保留旧结果，好过重跑一次没有字幕与章节的分析。 */
    @Test
    void alreadyReadyMediaWithFailingEnrichmentDoesNotSubmitNote() {
        MediaFile media = media(MediaImportStatus.READY);
        media.setContentHash("hash");
        media.setNoteProfileVersion(VideoNoteProfile.VERSION);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);
        doThrow(new ContentArtifactEnrichmentService.ArtifactEnrichmentException("manifest 写入失败", null))
                .when(artifactEnrichment).ensureArtifacts(any(MediaFile.class));

        service.acquire(MEDIA_ID);

        verify(noteTaskPort, never()).submitDefaultNote(MEDIA_ID);
    }

    /** V1 懒升级也必须尊重未就绪结果，不能绕过闸门启动一次残缺的 V2 分析。 */
    @Test
    void v1UpgradeWithBusyArtifactEnrichmentDoesNotSubmitNote() {
        MediaFile media = media(MediaImportStatus.READY);
        media.setContentHash("hash");
        media.setNoteProfileVersion("video-note:v1");
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);
        when(artifactEnrichment.ensureArtifacts(media))
                .thenReturn(ContentArtifactEnrichmentService.EnrichmentResult.IN_PROGRESS);

        service.acquire(MEDIA_ID);

        verify(noteTaskPort, never()).submitDefaultNote(MEDIA_ID);
    }

    /** 锁忙表示资产仍未就绪：媒体留在 MEDIA_READY，等恢复扫描重新经过同一闸门。 */
    @Test
    void busyArtifactEnrichmentDefersMediaReadyAndDoesNotSubmitNote() {
        MediaFile media = media(MediaImportStatus.MEDIA_READY);
        media.setContentHash("hash");
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);
        when(artifactEnrichment.ensureArtifacts(media))
                .thenReturn(ContentArtifactEnrichmentService.EnrichmentResult.IN_PROGRESS);

        service.acquire(MEDIA_ID);

        verify(mediaFileMapper).markNoteDeferred(MEDIA_ID, MediaImportStatus.MEDIA_READY,
                VideoNoteProfile.VERSION,
                VideoImportErrorCode.ARTIFACT_ENRICHMENT_FAILED.name(),
                VideoImportErrorCode.ARTIFACT_ENRICHMENT_FAILED.message());
        verify(noteTaskPort, never()).submitDefaultNote(MEDIA_ID);
    }

    /** 重试或恢复扫描可能把状态回退到待获取，但对象仍在 MinIO：不能重新下载。 */
    @Test
    void mediaWithStoredObjectIsPromotedInsteadOfDownloadedAgain() {
        MediaFile media = media(MediaImportStatus.PENDING_DISPATCH);
        media.setFilePath("http://localhost:9000/media/video-import/1/3/source.mp4");
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.PENDING_DISPATCH,
                MediaImportStatus.MEDIA_READY)).thenReturn(1);
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        service.acquire(MEDIA_ID);

        verify(acquirer, never()).acquireAndStore(any(MediaFile.class), any(), any());
        verify(mediaFileMapper).casStatus(MEDIA_ID, MediaImportStatus.PENDING_DISPATCH,
                MediaImportStatus.MEDIA_READY);
        verify(noteTaskPort).submitDefaultNote(MEDIA_ID);
    }

    @Test
    void alreadyStoredMediaIsNotDownloadedAgain() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.MEDIA_READY));
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        service.acquire(MEDIA_ID);

        verify(acquirer, never()).acquireAndStore(any(MediaFile.class), any(), any());
        // 已入库的媒体不会被重新取得执行权；默认笔记的状态推进不受影响。
        verify(mediaFileMapper, never()).casStatus(anyLong(), any(), eq(MediaImportStatus.ACQUIRING));
    }

    /**
     * 可重试失败：状态回到 QUEUED，并且**由服务层显式延迟重投**。
     *
     * <p>下载已移出 MQ 消费线程（D-064），异常不会再冒泡给 RocketMQ，因此重投不能再依赖消费端重试：
     * 必须显式发一条延迟消息，保留退避的同时维持"最多 max-attempts 次"的上限。
     */
    @Test
    void retryableAcquireFailureReturnsMediaToQueuedAndSchedulesDelayedRetry() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.QUEUED));
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING)).thenReturn(1);
        when(acquirer.acquireAndStore(any(MediaFile.class), any(), any())).thenThrow(new VideoSourceException(
                VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE));

        // 不再向调用方抛异常：消费线程已经确认消息，重投由服务层完成。
        assertDoesNotThrow(() -> service.acquire(MEDIA_ID));

        verify(mediaFileMapper).markAcquireFailed(eq(MEDIA_ID), eq(MediaImportStatus.ACQUIRING),
                eq(MediaImportStatus.QUEUED), eq(true),
                eq("SOURCE_TEMPORARY_UNAVAILABLE"), anyString());
        // 子项不跟随中间态（D-066）：QUEUED 只写在媒体行，子项保持 PENDING_DISPATCH。
        verify(itemMapper, never()).updatePendingToTerminalByMediaId(anyLong(), any(), anyBoolean(), any());
        verify(unitDispatcher).dispatchDelayed(MEDIA_ID, ImportUnitDispatcher.RETRY_DELAY_LEVEL);
    }

    @Test
    void nonRetryableAcquireFailureMarksMediaFailedWithoutRedelivery() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.QUEUED));
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING)).thenReturn(1);
        when(acquirer.acquireAndStore(any(MediaFile.class), any(), any())).thenThrow(new VideoSourceException(
                VideoImportErrorCode.SOURCE_NOT_FOUND));

        assertDoesNotThrow(() -> service.acquire(MEDIA_ID));

        verify(mediaFileMapper).markAcquireFailed(eq(MEDIA_ID), eq(MediaImportStatus.ACQUIRING),
                eq(MediaImportStatus.FAILED), eq(false), eq("SOURCE_NOT_FOUND"), anyString());
        // 确定性失败不重投：重试一万次结果都一样。
        verify(unitDispatcher, never()).dispatchDelayed(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * 自动重投次数耗尽后状态必须是 FAILED（不再占用 MQ 重投），但仍保留"可重试"标记：
     * 这是给用户重试入口看的，否则一次网络抖动会让这个视频永久无法导入（D-053）。
     */
    @Test
    void exhaustedAttemptsFailWithRetryableFlagKeptForUserRetry() {
        MediaFile media = media(MediaImportStatus.QUEUED);
        media.setAcquireAttemptCount(properties.getMaxAttempts() - 1);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING)).thenReturn(1);
        when(acquirer.acquireAndStore(any(MediaFile.class), any(), any())).thenThrow(new VideoSourceException(
                VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE));

        assertDoesNotThrow(() -> service.acquire(MEDIA_ID));

        verify(mediaFileMapper).markAcquireFailed(eq(MEDIA_ID), eq(MediaImportStatus.ACQUIRING),
                eq(MediaImportStatus.FAILED), eq(true),
                eq("SOURCE_TEMPORARY_UNAVAILABLE"), anyString());
        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.FAILED, true,
                "SOURCE_TEMPORARY_UNAVAILABLE");
    }

    /** 确定性失败的媒体不得被重新激活：再试一次结果只会一样。 */
    @Test
    void nonRetryableFailedMediaIsNotClaimed() {
        MediaFile media = media(MediaImportStatus.FAILED);
        media.setAcquireRetryable(false);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);

        service.acquire(MEDIA_ID);

        verify(acquirer, never()).acquireAndStore(any(MediaFile.class), any(), any());
        // 关键断言：不得尝试把"确定性失败"的媒体重新推进到 ACQUIRING。
        verify(mediaFileMapper, never()).casStatus(MEDIA_ID, MediaImportStatus.FAILED,
                MediaImportStatus.ACQUIRING);
    }

    /** 用户重新提交同一链接时媒体行会被复用：可重试失败的媒体必须真的再获取一次。 */
    @Test
    void retryableFailedMediaIsClaimedAgainOnResubmit() {
        MediaFile media = media(MediaImportStatus.FAILED);
        media.setAcquireRetryable(true);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media);
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.FAILED, MediaImportStatus.ACQUIRING))
                .thenReturn(1);
        when(acquirer.acquireAndStore(any(MediaFile.class), any(), any()))
                .thenReturn(new ImportMediaAcquirer.StoredMedia("http://minio/media/o.mp4", "hash", "video/mp4", 10L, COVER_URL));
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.ACCEPTED);

        service.acquire(MEDIA_ID);

        verify(acquirer).acquireAndStore(any(MediaFile.class), any(), any());
        verify(mediaFileMapper).markMediaReady(eq(MEDIA_ID), eq(MediaImportStatus.ACQUIRING),
                eq(MediaImportStatus.MEDIA_READY), anyString(), eq("hash"), eq(10L), eq(COVER_URL));
    }

    @Test
    void noteCapacityShortageKeepsMediaReadyForLaterRetry() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.QUEUED));
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING)).thenReturn(1);
        when(acquirer.acquireAndStore(any(MediaFile.class), any(), any()))
                .thenReturn(new ImportMediaAcquirer.StoredMedia("http://minio/media/o.mp4", "hash", "video/mp4", 10L, COVER_URL));
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.RATE_LIMITED);

        service.acquire(MEDIA_ID);

        // 后台容量不足不是失败：保持 MEDIA_READY 并留可重试标记，等恢复扫描重投。
        verify(mediaFileMapper).markNoteDeferred(eq(MEDIA_ID), eq(MediaImportStatus.MEDIA_READY),
                eq(VideoNoteProfile.VERSION), eq("NOTE_DISPATCH_DEFERRED"), anyString());
        verify(mediaFileMapper, never()).markNoteFailed(anyLong(), any(), any(), anyString(), anyBoolean(),
                anyString(), anyString());
        verify(mediaFileMapper, never()).casStatus(MEDIA_ID, MediaImportStatus.MEDIA_READY, MediaImportStatus.ANALYSIS_QUEUED);
    }

    @Test
    void noteDispatchFailureMarksMediaFailed() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media(MediaImportStatus.QUEUED));
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.ACQUIRING)).thenReturn(1);
        when(acquirer.acquireAndStore(any(MediaFile.class), any(), any()))
                .thenReturn(new ImportMediaAcquirer.StoredMedia("http://minio/media/o.mp4", "hash", "video/mp4", 10L, COVER_URL));
        when(noteTaskPort.submitDefaultNote(MEDIA_ID))
                .thenReturn(AnalysisDispatchService.SubmissionResult.FAILED);

        service.acquire(MEDIA_ID);

        verify(mediaFileMapper).markNoteFailed(eq(MEDIA_ID), eq(MediaImportStatus.MEDIA_READY),
                eq(MediaImportStatus.FAILED), eq(VideoNoteProfile.VERSION), eq(true),
                eq("NOTE_PROCESSING_FAILED"), anyString());
    }

    @Test
    void deletedMediaTerminatesWithoutSideEffects() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(null);

        service.acquire(MEDIA_ID);

        verify(acquirer, never()).acquireAndStore(any(MediaFile.class), any(), any());
        verify(mediaFileMapper, never()).casStatus(anyLong(), any(), any());
    }

    private MediaFile media(MediaImportStatus status) {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(1L);
        media.setStatus(status);
        media.setPlatform(com.example.server.source.VideoPlatform.BILIBILI);
        media.setResourceType("UGC_VIDEO");
        media.setExternalResourceId("BV1sjen6QEQ7");
        media.setExternalUnitId("41820686637");
        media.setCanonicalUrl("https://www.bilibili.com/video/BV1sjen6QEQ7?p=1");
        media.setAcquireAttemptCount(0);
        return media;
    }
}
