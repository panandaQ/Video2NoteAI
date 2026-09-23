package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.config.VideoNoteProperties;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.entity.MediaFile;
import com.example.server.entity.VideoImportItem;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.MediaIndexService;
import com.example.server.service.MediaService;
import com.example.server.source.VideoPlatform;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 恢复扫描契约（契约 §7.5）：每一类中间态都必须有确定的收敛动作。
 *
 * <p>测试重点不是"扫描跑了"，而是每条分支的<b>动作边界</b>：能推进就推进、该重投才重投、
 * 有对象就绝不重新下载、预算耗尽必须转成用户可重试的失败。
 */
class ImportRecoveryScannerTest {

    private static final Long IMPORT_ID = 11L;
    private static final Long MEDIA_ID = 22L;

    private final VideoImportJobMapper jobMapper = mock(VideoImportJobMapper.class);
    private final VideoImportItemMapper itemMapper = mock(VideoImportItemMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final ImportResolveDispatcher resolveDispatcher = mock(ImportResolveDispatcher.class);
    private final ImportUnitDispatcher unitDispatcher = mock(ImportUnitDispatcher.class);
    private final ImportAcquireService acquireService = mock(ImportAcquireService.class);
    private final ImportJobAggregator aggregator = mock(ImportJobAggregator.class);
    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final AnalysisDispatchService analysisDispatchService = mock(AnalysisDispatchService.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ImportUnitLock unitLock = mock(ImportUnitLock.class);
    private final ImportTerminalEventPublisher terminalPublisher = mock(ImportTerminalEventPublisher.class);
    private final MediaIndexService mediaIndexService = mock(MediaIndexService.class);
    private final com.example.server.service.AgentBudgetService budgetService =
            mock(com.example.server.service.AgentBudgetService.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final VideoImportProperties properties = properties();
    private final VideoNoteProperties noteProperties = noteProperties();

    private final ImportRecoveryScanner scanner = new ImportRecoveryScanner(
            jobMapper, itemMapper, mediaFileMapper, resolveDispatcher, unitDispatcher,
            acquireService, unitLock, aggregator, checkpointService, analysisDispatchService,
            mediaService, minioUtils, redisTemplate, properties, noteProperties, terminalPublisher,
            mediaIndexService, budgetService);

    @org.junit.jupiter.api.BeforeEach
    void indexIsReadyByDefault() {
        // 索引就绪是完成边界的一部分（D-069）；"索引未就绪则不推进"由专门用例覆盖。
        when(mediaIndexService.ensureIndexed(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        // 判死阈值（D-076 修订）：mock 默认返回配置值 1800s，判死/存活用例按需覆盖。
        when(budgetService.recoveryStaleThresholdSeconds(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(1800L);
    }

    @Test
    void stalePendingDispatchJobIsRedispatched() {
        VideoImportJob job = job(VideoImportJobStatus.PENDING_DISPATCH, 0);
        stubStaleJobs(job);

        scanner.scan();

        verify(resolveDispatcher).dispatch(job);
    }

    @Test
    void staleResolvingJobIsResetToQueuedAndRedispatched() {
        VideoImportJob job = job(VideoImportJobStatus.RESOLVING, 0);
        stubStaleJobs(job);
        when(jobMapper.casStatus(IMPORT_ID, VideoImportJobStatus.RESOLVING, VideoImportJobStatus.QUEUED))
                .thenReturn(1);

        scanner.scan();

        verify(resolveDispatcher).redispatch(job);
    }

    /** 契约只写到 RESOLVING，但可重试解析失败会把任务放回 QUEUED，MQ 重投耗尽后没有任何东西会再投递它。 */
    @Test
    void staleQueuedJobIsRedispatchedWithinBudget() {
        VideoImportJob job = job(VideoImportJobStatus.QUEUED, 0);
        stubStaleJobs(job);

        scanner.scan();

        verify(jobMapper).incrementAttempt(IMPORT_ID);
        verify(resolveDispatcher).redispatch(job);
        verify(jobMapper, never()).casStatusAndReleaseActiveKey(anyLong(), any(), any());
    }

    @Test
    void staleQueuedJobBecomesRetryableFailureWhenBudgetIsExhausted() {
        VideoImportJob job = job(VideoImportJobStatus.QUEUED, 3);
        stubStaleJobs(job);
        when(jobMapper.casStatusAndReleaseActiveKey(IMPORT_ID,
                VideoImportJobStatus.QUEUED, VideoImportJobStatus.FAILED)).thenReturn(1);

        scanner.scan();

        verify(jobMapper).updateError(IMPORT_ID,
                VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE.name(),
                "自动重投未完成解析，可手动重试", true);
        verify(resolveDispatcher, never()).redispatch(any());
        verify(jobMapper, never()).incrementAttempt(anyLong());
    }

    @Test
    void staleDispatchFailedJobReattachesActiveKeyAndRedispatches() {
        VideoImportJob job = job(VideoImportJobStatus.DISPATCH_FAILED, 0);
        stubStaleJobs(job);
        when(jobMapper.casStatusAndAttachActiveKey(eq(IMPORT_ID),
                eq(VideoImportJobStatus.DISPATCH_FAILED), eq(VideoImportJobStatus.PENDING_DISPATCH),
                anyString())).thenReturn(1);
        when(jobMapper.selectById(IMPORT_ID)).thenReturn(job);

        scanner.scan();

        verify(resolveDispatcher).dispatch(job);
    }

    /** 下载已完成但进程死在写状态之前：对象在存储里，必须直接推进，绝不重新下载。 */
    @Test
    void staleAcquiringMediaWithStoredObjectIsPromotedWithoutDownload() {
        MediaFile media = media(MediaImportStatus.ACQUIRING);
        media.setFilePath("http://127.0.0.1:9000/media/video-import/1/22/source.mp4");
        stubStaleMedia(MediaImportStatus.ACQUIRING, media);
        when(minioUtils.objectExists(media.getFilePath())).thenReturn(true);

        scanner.scan();

        verify(acquireService).acquire(MEDIA_ID);
        verify(mediaFileMapper, never()).casStatus(anyLong(),
                eq(MediaImportStatus.ACQUIRING), eq(MediaImportStatus.QUEUED));
        verify(unitDispatcher, never()).dispatch(any(), any(), any());
    }

    /** 对象在、笔记也已经完成（状态被回退过）：直接 READY，不重新下载也不再跑一遍分析。 */
    @Test
    void staleAcquiringMediaWithStoredObjectAndCompletedNoteGoesStraightToReady() {
        MediaFile media = media(MediaImportStatus.ACQUIRING);
        media.setFilePath("http://127.0.0.1:9000/media/video-import/1/22/source.mp4");
        stubStaleMedia(MediaImportStatus.ACQUIRING, media);
        when(minioUtils.objectExists(media.getFilePath())).thenReturn(true);
        when(checkpointService.loadResult(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(completedNote());
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.ACQUIRING, MediaImportStatus.READY))
                .thenReturn(1);

        scanner.scan();

        verify(acquireService, never()).acquire(anyLong());
        verify(mediaFileMapper).markNoteCompleted(MEDIA_ID,
                MediaImportStatus.READY, MediaImportStatus.READY, VideoNoteProfile.VERSION);
        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.READY, false, null);
    }

    /**
     * 下载权锁被持有 = 进程还活着、正在下载。
     *
     * <p>下载期间 {@code updated_at} 不变，只看超时会把健康的长下载判成卡死：重置状态、重投、
     * 并白白消耗恢复预算（真到需要重投时已经耗尽）。所以扫描必须先看锁。
     */
    @Test
    void acquiringMediaWithDownloadLockIsSkipped() {
        MediaFile media = media(MediaImportStatus.ACQUIRING);
        stubStaleMedia(MediaImportStatus.ACQUIRING, media);
        when(unitLock.isUnitLocked(anyLong(), anyString())).thenReturn(true);

        scanner.scan();

        verify(mediaFileMapper, never()).casStatus(anyLong(),
                eq(MediaImportStatus.ACQUIRING), eq(MediaImportStatus.QUEUED));
        verify(mediaFileMapper, never()).incrementAcquireAttempt(anyLong());
        verify(unitDispatcher, never()).dispatch(any(), any(), any());
        verify(acquireService, never()).acquire(anyLong());
    }

    @Test
    void staleAcquiringMediaWithoutObjectIsResetAndRedispatched() {        MediaFile media = media(MediaImportStatus.ACQUIRING);
        media.setFilePath("http://127.0.0.1:9000/media/video-import/1/22/source.mp4");
        stubStaleMedia(MediaImportStatus.ACQUIRING, media);
        when(minioUtils.objectExists(anyString())).thenReturn(false);
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.ACQUIRING, MediaImportStatus.QUEUED))
                .thenReturn(1);
        when(itemMapper.findByMediaId(MEDIA_ID)).thenReturn(List.of(item(MediaImportStatus.ACQUIRING)));

        scanner.scan();

        verify(unitDispatcher).dispatch(eq(IMPORT_ID), eq(MEDIA_ID), anyString());
        verify(acquireService, never()).acquire(anyLong());
    }

    @Test
    void staleQueuedMediaIsRedispatched() {
        MediaFile media = media(MediaImportStatus.QUEUED);
        stubStaleMedia(MediaImportStatus.QUEUED, media);
        when(itemMapper.findByMediaId(MEDIA_ID)).thenReturn(List.of(item(MediaImportStatus.QUEUED)));

        scanner.scan();

        verify(mediaFileMapper).incrementAcquireAttempt(MEDIA_ID);
        verify(unitDispatcher).dispatch(eq(IMPORT_ID), eq(MEDIA_ID), anyString());
    }

    @Test
    void mediaWithExhaustedAcquireBudgetBecomesRetryableFailure() {
        MediaFile media = media(MediaImportStatus.QUEUED);
        media.setAcquireAttemptCount(3);
        stubStaleMedia(MediaImportStatus.QUEUED, media);
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.QUEUED, MediaImportStatus.FAILED))
                .thenReturn(1);

        scanner.scan();

        verify(mediaFileMapper).markAcquireFailed(MEDIA_ID,
                MediaImportStatus.FAILED, MediaImportStatus.FAILED, true,
                VideoImportErrorCode.MEDIA_ACQUIRE_FAILED.name(),
                VideoImportErrorCode.MEDIA_ACQUIRE_FAILED.message());
        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.FAILED, true,
                VideoImportErrorCode.MEDIA_ACQUIRE_FAILED.name());
        verify(unitDispatcher, never()).dispatch(any(), any(), any());
    }

    /** 媒体已入库但笔记没投出去（包括资产锁忙而延期）：重新进入 acquire 与资产闸门。 */
    @Test
    void staleMediaReadyIncludingArtifactDeferralReentersAcquireGate() {
        MediaFile media = media(MediaImportStatus.MEDIA_READY);
        stubStaleMedia(MediaImportStatus.MEDIA_READY, media);
        when(checkpointService.loadResult(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(null);

        scanner.scan();

        verify(acquireService).acquire(MEDIA_ID);
    }

    /** 笔记其实已经完成，只是媒体状态没写对：按 Checkpoint 直接推进 READY，不重复分析。 */
    @Test
    void mediaWithCompletedCheckpointIsPromotedToReady() {
        MediaFile media = media(MediaImportStatus.MEDIA_READY);
        stubStaleMedia(MediaImportStatus.MEDIA_READY, media);
        when(checkpointService.loadResult(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(completedNote());
        when(mediaFileMapper.casStatus(MEDIA_ID, MediaImportStatus.MEDIA_READY, MediaImportStatus.READY))
                .thenReturn(1);

        scanner.scan();

        verify(mediaFileMapper).markNoteCompleted(MEDIA_ID,
                MediaImportStatus.READY, MediaImportStatus.READY, VideoNoteProfile.VERSION);
        verify(itemMapper).updatePendingToTerminalByMediaId(MEDIA_ID, MediaImportStatus.READY, false, null);
        verify(mediaService).invalidateUserList(anyLong());
        verify(acquireService, never()).acquire(anyLong());
    }

    /** 活跃键还在说明分析任务确实在路上：只等待，不重投，避免重复 ASR/OCR。 */
    @Test
    void noteInFlightWithActiveKeyIsLeftAlone() {
        MediaFile media = media(MediaImportStatus.ANALYZING);
        media.setUpdatedAt(LocalDateTime.now().minusMinutes(30));
        stubStaleMedia(MediaImportStatus.ANALYZING, media);
        when(checkpointService.loadResult(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(null);
        when(analysisDispatchService.isActive(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(true);
        when(checkpointService.loadLatestProgressAt(MEDIA_ID))
                .thenReturn(LocalDateTime.now().minusMinutes(2));

        scanner.scan();

        verify(acquireService, never()).acquire(anyLong());
    }

    /** 活跃键不存在 = 分析消息已经丢了：按预算重投。 */
    @Test
    void noteInFlightWithoutActiveKeyIsRedispatched() {
        MediaFile media = media(MediaImportStatus.ANALYSIS_QUEUED);
        stubStaleMedia(MediaImportStatus.ANALYSIS_QUEUED, media);
        when(checkpointService.loadResult(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(null);
        when(analysisDispatchService.isActive(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(false);

        scanner.scan();

        verify(acquireService).acquire(MEDIA_ID);
    }

    /**
     * 活跃键还在，但检查点长时间没有推进 = 持键进程已经死了。
     *
     * <p>这是真实缺陷的回归：分析跑到一半应用被重启，活跃键（TTL 6 小时）仍在，重投一律被
     * "已有活跃任务"挡回，媒体就永久停在 {@code ANALYZING}。现在必须先释放僵尸键再重投。
     */
    @Test
    void staleActiveKeyWithNoProgressIsReleasedAndRedispatched() {
        MediaFile media = media(MediaImportStatus.ANALYZING);
        media.setUpdatedAt(LocalDateTime.now().minusHours(2));
        stubStaleMedia(MediaImportStatus.ANALYZING, media);
        when(checkpointService.loadResult(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(null);
        when(analysisDispatchService.isActive(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(true);
        when(checkpointService.loadLatestProgressAt(MEDIA_ID))
                .thenReturn(LocalDateTime.now().minusMinutes(45));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(analysisDispatchService.releaseStaleActiveKey(
                MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE)).thenReturn(true);

        scanner.scan();

        verify(analysisDispatchService).releaseStaleActiveKey(
                MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE);
        verify(acquireService).acquire(MEDIA_ID);
    }

    /** 检查点仍在推进（真在跑）时只等待，既不释放键也不重投。 */
    @Test
    void activeKeyWithFreshProgressIsLeftAlone() {
        MediaFile media = media(MediaImportStatus.ANALYZING);
        media.setUpdatedAt(LocalDateTime.now().minusMinutes(30));
        stubStaleMedia(MediaImportStatus.ANALYZING, media);
        when(checkpointService.loadResult(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(null);
        when(analysisDispatchService.isActive(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(true);
        when(checkpointService.loadLatestProgressAt(MEDIA_ID))
                .thenReturn(LocalDateTime.now().minusMinutes(1));

        scanner.scan();

        verify(analysisDispatchService, never()).releaseStaleActiveKey(anyLong(), anyString(), any());
        verify(acquireService, never()).acquire(anyLong());
    }

    /** 同一个静默窗口内不重复判死：拿不到恢复名额就跳过，避免重复投递昂贵的分析任务。 */
    @Test
    void recoverySlotPreventsRepeatedKillJudgements() {
        MediaFile media = media(MediaImportStatus.ANALYZING);
        media.setUpdatedAt(LocalDateTime.now().minusHours(2));
        stubStaleMedia(MediaImportStatus.ANALYZING, media);
        when(checkpointService.loadResult(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(null);
        when(analysisDispatchService.isActive(MEDIA_ID, VideoNoteProfile.GOAL, VideoNoteProfile.MODE))
                .thenReturn(true);
        when(checkpointService.loadLatestProgressAt(MEDIA_ID))
                .thenReturn(LocalDateTime.now().minusMinutes(45));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        scanner.scan();

        verify(analysisDispatchService, never()).releaseStaleActiveKey(anyLong(), anyString(), any());
        verify(acquireService, never()).acquire(anyLong());
    }

    /** 媒体行已被删除的悬挂子项不能永远等下去，否则父任务永久停在处理中。 */
    @Test
    void orphanItemIsFailedWithMediaDeletedAndParentRecomputed() {
        VideoImportItem orphan = item(MediaImportStatus.ANALYZING);
        when(itemMapper.findNonTerminal(anyInt())).thenReturn(List.of(orphan));
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(null);
        when(itemMapper.casItemStatus(IMPORT_ID, MEDIA_ID, MediaImportStatus.ANALYZING,
                MediaImportStatus.FAILED, false, VideoImportErrorCode.MEDIA_DELETED.name()))
                .thenReturn(1);

        scanner.scan();

        verify(aggregator).recompute(IMPORT_ID);
    }

    @Test
    void contradictoryTerminalJobIsReconciledFromItems() {
        VideoImportJob job = job(VideoImportJobStatus.FAILED, 0);
        when(jobMapper.findTerminalContradictions(anyInt())).thenReturn(List.of(job));

        scanner.scan();

        verify(aggregator).recompute(IMPORT_ID);
    }

    @Test
    void staleNonTerminalJobIsReconciled() {
        VideoImportJob job = job(VideoImportJobStatus.PROCESSING, 0);
        when(jobMapper.findNonTerminalStale(any(), anyInt())).thenReturn(List.of(job));

        scanner.scan();

        verify(aggregator).recompute(IMPORT_ID);
    }

    /** 恢复逻辑必须比被恢复的链路更耐异常：一个阶段崩了，后面的阶段照常跑。 */
    @Test
    void phaseFailureDoesNotAbortRemainingPhases() {
        when(jobMapper.findStaleByStatuses(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("数据库瞬时不可用"));
        VideoImportJob queued = job(VideoImportJobStatus.QUEUED, 0);
        when(jobMapper.findNonTerminalStale(any(), anyInt())).thenReturn(List.of(queued));

        scanner.scan();

        // 第一个阶段抛异常，后续阶段仍然执行（这里以对账阶段为证）。
        verify(aggregator).recompute(IMPORT_ID);
    }

    /**
     * 空闲退避：连续多轮无事可做时跳轮，减少"零命中也在查"的固定轮询。
     *
     * <p>退避不能影响语义：一旦有动作立即回到基础频率。这里验证跳轮确实发生了
     * （第 4 轮的查询次数不再按轮数线性增长）。
     */
    @Test
    void idleRoundsAreBackedOffAfterConsecutiveEmptyScans() {
        VideoImportProperties props = properties();
        props.setRecoveryIdleBackoffMax(2);
        ImportRecoveryScanner backoffScanner = new ImportRecoveryScanner(
                jobMapper, itemMapper, mediaFileMapper, resolveDispatcher, unitDispatcher,
                acquireService, unitLock, aggregator, checkpointService, analysisDispatchService,
                mediaService, minioUtils, redisTemplate, props, noteProperties, terminalPublisher,
                mediaIndexService, budgetService);

        for (int round = 0; round < 6; round++) {
            backoffScanner.scan();
        }

        // 6 轮里至少有一轮被退避跳过，因此状态查询次数少于 6 次。
        verify(jobMapper, org.mockito.Mockito.atMost(5))
                .findStaleByStatuses(any(), any(), anyInt());
    }

    /** 有动作时不做退避：连续两轮都发现并处理了任务。 */
    @Test
    void activeRoundsAreNeverBackedOff() {
        VideoImportJob job = job(VideoImportJobStatus.QUEUED, 0);
        stubStaleJobs(job);

        scanner.scan();
        scanner.scan();

        verify(jobMapper, times(2)).findStaleByStatuses(any(), any(), anyInt());
    }

    @Test
    void idleScanPerformsNoWrites() {
        scanner.scan();

        verify(resolveDispatcher, never()).dispatch(any());
        verify(resolveDispatcher, never()).redispatch(any());
        verify(unitDispatcher, never()).dispatch(any(), any(), any());
        verify(acquireService, never()).acquire(anyLong());
        verify(aggregator, never()).recompute(anyLong());
        verify(mediaFileMapper, never()).casStatus(anyLong(), any(), any());
        verify(jobMapper, never()).casStatus(anyLong(), any(), any());
        verify(itemMapper, times(0)).casItemStatus(anyLong(), anyLong(), any(), any(), anyBoolean(), any());
    }

    private VideoImportProperties properties() {
        VideoImportProperties props = new VideoImportProperties();
        props.setRecoveryStaleSeconds(60);
        props.setRecoveryBatchSize(100);
        props.setMaxAttempts(3);
        // 默认关闭退避，保证"每轮必扫"的既有断言不被退避影响；退避本身有专门用例。
        props.setRecoveryIdleBackoffMax(1);
        return props;
    }

    private VideoNoteProperties noteProperties() {
        VideoNoteProperties props = new VideoNoteProperties();
        props.setMaxAttempts(3);
        props.setRecoveryStaleSeconds(1800);
        return props;
    }

    /** 扫描现在一条查询取回多种父任务状态，按行上的 status 走分支。 */
    private void stubStaleJobs(VideoImportJob... jobs) {
        when(jobMapper.findStaleByStatuses(any(), any(), anyInt())).thenReturn(List.of(jobs));
    }

    /** 媒体侧四条查询用不同状态集合区分：按"集合里含该状态"匹配。 */
    private void stubStaleMedia(MediaImportStatus status, MediaFile media) {
        when(mediaFileMapper.findStaleByStatuses(
                org.mockito.ArgumentMatchers.<java.util.Collection<MediaImportStatus>>argThat(
                        statuses -> statuses != null && statuses.contains(status)),
                any(), anyInt()))
                .thenReturn(List.of(media));
    }

    private VideoImportJob job(VideoImportJobStatus status, int attemptCount) {
        VideoImportJob job = new VideoImportJob();
        job.setId(IMPORT_ID);
        job.setUserId(7L);
        job.setStatus(status);
        job.setRequestHash("hash");
        job.setTraceId("trace");
        job.setAttemptCount(attemptCount);
        return job;
    }

    private VideoImportItem item(MediaImportStatus status) {
        VideoImportItem item = new VideoImportItem();
        item.setImportId(IMPORT_ID);
        item.setMediaId(MEDIA_ID);
        item.setItemStatus(status);
        return item;
    }

    private MediaFile media(MediaImportStatus status) {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(7L);
        media.setStatus(status);
        media.setPlatform(VideoPlatform.BILIBILI);
        media.setResourceType("UGC_VIDEO");
        media.setExternalResourceId("BV1xx411c7mD");
        media.setExternalUnitId("41820686637");
        media.setAcquireAttemptCount(0);
        media.setNoteAttemptCount(0);
        return media;
    }

    private AgentState completedNote() {
        return new AgentState(VideoNoteProfile.GOAL, null,
                new AnalysisResult("笔记", List.of("结论"), List.of(), List.of(), List.of()),
                null, 1);
    }
}
