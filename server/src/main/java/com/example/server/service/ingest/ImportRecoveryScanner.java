package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.config.VideoNoteProperties;
import com.example.server.dto.AgentState;
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
import com.example.server.utils.MinioUtils;
import com.example.server.utils.VideoImportKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入链路恢复扫描（契约 §7.5）。
 *
 * <p>存在的理由：整条链路是“数据库状态 + MQ 消息 + 外部进程”的组合，任何一步都可能只完成一半——
 * 消息发出但没人消费、进程在下载中被杀、MQ 重投次数耗尽、后台容量不足被延迟。这些中间态本身是合法的，
 * 但必须有东西把它们推回链路，否则只能靠人工写 SQL。本类就是这个“东西”，只处理数据库状态，不猜测原因。
 *
 * <p>三条设计约束：
 * <ul>
 *   <li><b>不做重活</b>：扫描只做状态判断、消息重投和默认笔记提交，绝不在调度线程里下载视频；
 *       真正的下载永远由 {@code ACQUIRING} 状态的消费者完成；</li>
 *   <li><b>幂等</b>：每个动作都先做带旧状态条件的 CAS，多实例并发扫描或与消费者竞争都只会有一个生效；</li>
 *   <li><b>有预算</b>：自动重投消耗 {@code attempt_count}/{@code acquire_attempt_count}，
 *       耗尽后转成“可重试失败”，把决定权交回用户，而不是无限重投（D-051/D-053）。</li>
 * </ul>
 *
 * <p>单个阶段或单条记录失败不能中断整轮扫描：恢复逻辑必须比被恢复的链路更能容忍异常。
 */
@Component
public class ImportRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(ImportRecoveryScanner.class);

    /** 需要重新投递获取消息的媒体状态：三种状态都表示“消息不在路上”。 */
    private static final List<MediaImportStatus> ACQUIRE_PENDING_STATUSES = List.of(
            MediaImportStatus.PENDING_DISPATCH,
            MediaImportStatus.QUEUED,
            MediaImportStatus.DISPATCH_FAILED);

    /** 默认笔记已经投递、正在等待结果的状态。 */
    private static final List<MediaImportStatus> NOTE_IN_FLIGHT_STATUSES = List.of(
            MediaImportStatus.ANALYSIS_QUEUED,
            MediaImportStatus.ANALYZING);

    /** 父任务里“没有消息在路上或刚好断在半路”的状态，合并成一条查询。 */
    private static final List<VideoImportJobStatus> STALE_PARENT_STATUSES = List.of(
            VideoImportJobStatus.PENDING_DISPATCH,
            VideoImportJobStatus.DISPATCH_FAILED,
            VideoImportJobStatus.QUEUED,
            VideoImportJobStatus.RESOLVING);

    private final VideoImportJobMapper jobMapper;
    private final VideoImportItemMapper itemMapper;
    private final MediaFileMapper mediaFileMapper;
    private final ImportResolveDispatcher resolveDispatcher;
    private final ImportUnitDispatcher unitDispatcher;
    private final ImportAcquireService acquireService;
    private final ImportUnitLock unitLock;
    private final ImportJobAggregator aggregator;
    private final AgentCheckpointService checkpointService;
    private final AnalysisDispatchService analysisDispatchService;
    private final MediaService mediaService;
    private final MinioUtils minioUtils;
    private final StringRedisTemplate redisTemplate;
    private final VideoImportProperties properties;
    private final VideoNoteProperties noteProperties;
    private final ImportTerminalEventPublisher terminalPublisher;
    private final MediaIndexService mediaIndexService;
    private final com.example.server.service.AgentBudgetService budgetService;

    /** 连续无事可做的轮数，用于空闲退避；有任何动作立即归零。 */
    private int consecutiveIdleRounds;
    /** 退避期间的轮次计数（用于取模决定本轮的扫与不扫）。 */
    private int roundsSinceIdleCheck;

    public ImportRecoveryScanner(VideoImportJobMapper jobMapper,
                                 VideoImportItemMapper itemMapper,
                                 MediaFileMapper mediaFileMapper,
                                 ImportResolveDispatcher resolveDispatcher,
                                 ImportUnitDispatcher unitDispatcher,
                                 ImportAcquireService acquireService,
                                 ImportUnitLock unitLock,
                                 ImportJobAggregator aggregator,
                                 AgentCheckpointService checkpointService,
                                 AnalysisDispatchService analysisDispatchService,
                                 MediaService mediaService,
                                 MinioUtils minioUtils,
                                 StringRedisTemplate redisTemplate,
                                 VideoImportProperties properties,
                                 VideoNoteProperties noteProperties,
                                 ImportTerminalEventPublisher terminalPublisher,
                                 MediaIndexService mediaIndexService,
                                 com.example.server.service.AgentBudgetService budgetService) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.resolveDispatcher = resolveDispatcher;
        this.unitDispatcher = unitDispatcher;
        this.acquireService = acquireService;
        this.unitLock = unitLock;
        this.aggregator = aggregator;
        this.checkpointService = checkpointService;
        this.analysisDispatchService = analysisDispatchService;
        this.mediaService = mediaService;
        this.minioUtils = minioUtils;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.noteProperties = noteProperties;
        this.terminalPublisher = terminalPublisher;
        this.mediaIndexService = mediaIndexService;
        this.budgetService = budgetService;
    }

    /** 固定间隔扫描；间隔来自 {@code video.import.recovery-delay-seconds}，默认 30 秒。 */
    @Scheduled(fixedDelayString = "#{${video.import.recovery-delay-seconds:30} * 1000}")
    public void scan() {
        if (skipIdleRound()) {
            return;
        }
        LocalDateTime staleBefore = LocalDateTime.now()
                .minusSeconds(properties.getRecoveryStaleSeconds());
        int batch = properties.getRecoveryBatchSize();
        Report report = new Report();
        long startedAt = System.currentTimeMillis();

        phase("parent-stale", report, () -> recoverStaleParents(staleBefore, batch, report));
        phase("media-acquiring", report, () -> recoverStaleAcquiring(staleBefore, batch, report));
        phase("media-acquire-pending", report, () -> recoverStaleAcquirePending(staleBefore, batch, report));
        phase("media-note-pending", report, () -> recoverStaleNotePending(staleBefore, batch, report));
        phase("media-note-in-flight", report, () -> recoverStaleNoteInFlight(staleBefore, batch, report));
        phase("orphan-items", report, () -> failOrphanItems(batch, report));
        phase("parent-reconcile", report, () -> reconcileParents(staleBefore, batch, report));

        if (report.acted == 0) {
            consecutiveIdleRounds++;
            // 只跳过不动作也要留痕：这是"扫描看到了、但因为有人在正常处理而没动手"的唯一证据
            // （例如 ACQUIRING 媒体持有下载锁）。整轮无动作且无跳过才降到 DEBUG。
            if (report.skipped == 0) {
                log.debug("video_import_recovery_idle staleSeconds={} elapsedMs={} idleRounds={}",
                        properties.getRecoveryStaleSeconds(), System.currentTimeMillis() - startedAt,
                        consecutiveIdleRounds);
            } else {
                log.info("video_import_recovery_waiting skipped={} elapsedMs={} idleRounds={}",
                        report.skipped, System.currentTimeMillis() - startedAt, consecutiveIdleRounds);
            }
            return;
        }
        consecutiveIdleRounds = 0;
        roundsSinceIdleCheck = 0;
        log.info("video_import_recovery_scan acted={} skipped={} failed={} elapsedMs={} detail={}",
                report.acted, report.skipped, report.failed,
                System.currentTimeMillis() - startedAt, report.detail());
    }

    /**
     * 空闲退避：连续多轮无事可做时，降低扫描频率。
     *
     * <p>恢复扫描是"固定节奏轮询"：每轮要对多种状态各查一次，**即使一条卡住的记录都没有**。
     * 空闲 3 轮后按倍数跳轮（倍数上限由 {@code video.import.recovery-idle-backoff-max} 控制，
     * 默认 2；设为 1 即关闭退避）。一旦有任何动作就立刻回到基础频率——退避只影响"空闲时的探测频率"，
     * 不改变任何恢复语义，最坏情况只是把卡死记录的发现时间从 1 个周期推迟到"倍数"个周期。
     */
    private boolean skipIdleRound() {
        int backoffMax = Math.max(1, properties.getRecoveryIdleBackoffMax());
        int factor = Math.min(backoffMax, 1 + consecutiveIdleRounds / 3);
        if (factor <= 1) {
            return false;
        }
        roundsSinceIdleCheck++;
        boolean skip = roundsSinceIdleCheck % factor != 0;
        if (skip) {
            log.debug("video_import_recovery_backoff idleRounds={} factor={}", consecutiveIdleRounds, factor);
        }
        return skip;
    }

    // ---------------------------------------------------------------- 父任务

    /**
     * 超时父任务：一条查询取回四种状态，再按行上的状态走各自分支。
     *
     * <p>合并查询是为了让"固定节奏轮询"的语句数与状态种类数解耦——每轮只发 1 条，而不是 4 条。
     */
    private void recoverStaleParents(LocalDateTime staleBefore, int batch, Report report) {
        List<VideoImportJob> stale = jobMapper.findStaleByStatuses(
                STALE_PARENT_STATUSES, staleBefore, batch);
        for (VideoImportJob job : stale) {
            switch (job.getStatus()) {
                case PENDING_DISPATCH -> {
                    report.act("parent-pending-dispatch", job.getId());
                    resolveDispatcher.dispatch(job);
                }
                case DISPATCH_FAILED -> requeueDispatchFailed(job, report);
                case RESOLVING -> resetResolving(job, report);
                case QUEUED -> redispatchQueued(job, report);
                default -> log.debug("video_import_recovery_unexpected_parent_status importId={} status={}",
                        job.getId(), job.getStatus());
            }
        }
    }

    /** {@code DISPATCH_FAILED}：重新占用活跃键回到 {@code PENDING_DISPATCH} 后重投。 */
    private void requeueDispatchFailed(VideoImportJob job, Report report) {
        if (!consumeJobAttempt(job, "parent-dispatch-failed", report)) {
            return;
        }
        String activeKey = VideoImportKeys.activeRequestKey(job.getUserId(), job.getRequestHash());
        int moved;
        try {
            moved = jobMapper.casStatusAndAttachActiveKey(job.getId(),
                    VideoImportJobStatus.DISPATCH_FAILED, VideoImportJobStatus.PENDING_DISPATCH,
                    activeKey);
        } catch (DuplicateKeyException e) {
            // 同一 URL 已有另一个活跃任务：让它跑完，本任务保持原状。
            log.info("video_import_recovery_dispatch_failed_reused importId={}", job.getId());
            report.skip();
            return;
        }
        if (moved == 0) {
            return;
        }
        report.act("parent-dispatch-failed-requeued", job.getId());
        resolveDispatcher.dispatch(jobMapper.selectById(job.getId()));
    }

    /** 超时 {@code RESOLVING}：CAS 回 {@code QUEUED} 后重新解析（契约 §7.5）。 */
    private void resetResolving(VideoImportJob job, Report report) {
        if (jobMapper.casStatus(job.getId(),
                VideoImportJobStatus.RESOLVING, VideoImportJobStatus.QUEUED) == 0) {
            return;
        }
        report.act("parent-resolving-reset", job.getId());
        resolveDispatcher.redispatch(job);
    }

    /**
     * 超时 {@code QUEUED}：解析消息已经不在路上（重投次数耗尽或 Broker 丢消息）。
     *
     * <p>契约 §7.5 只写到 {@code RESOLVING}，但可重试解析异常会把父任务放回 {@code QUEUED} 并抛给 MQ，
     * MQ 重投上限是 2 次——第 3 次之后没有任何东西会再投递它，任务会永久停在 {@code QUEUED}，
     * 且重试接口对该状态返回 409。因此恢复扫描必须把 {@code QUEUED} 也当作可恢复状态（D-051）。
     */
    private void redispatchQueued(VideoImportJob job, Report report) {
        if (!consumeJobAttempt(job, "parent-queued", report)) {
            return;
        }
        report.act("parent-queued-redispatched", job.getId());
        resolveDispatcher.redispatch(job);
    }

    /**
     * 消耗一次自动恢复预算。
     *
     * <p>预算耗尽时不再重投，而是转成“可重试失败”：错误码取可重试的
     * {@code SOURCE_TEMPORARY_UNAVAILABLE}，文案说明是自动重投耗尽，用户可显式重试（D-053）。
     *
     * @return 是否还有预算继续重投
     */
    private boolean consumeJobAttempt(VideoImportJob job, String stage, Report report) {
        int attempts = job.getAttemptCount() == null ? 0 : job.getAttemptCount();
        if (attempts >= properties.getMaxAttempts()) {
            jobMapper.updateError(job.getId(),
                    VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE.name(),
                    "自动重投未完成解析，可手动重试", true);
            boolean written = jobMapper.casStatusAndReleaseActiveKey(job.getId(),
                    VideoImportJobStatus.QUEUED, VideoImportJobStatus.FAILED) > 0
                    || jobMapper.casStatusAndReleaseActiveKey(job.getId(),
                    VideoImportJobStatus.DISPATCH_FAILED, VideoImportJobStatus.FAILED) > 0;
            if (!written) {
                log.info("video_import_recovery_attempts_exhausted_without_transition importId={} stage={}",
                        job.getId(), stage);
                report.skip();
                return false;
            }
            report.act("parent-recovery-exhausted", job.getId());
            terminalPublisher.publishFailed(job.getId(),
                    VideoImportErrorCode.SOURCE_TEMPORARY_UNAVAILABLE.message());
            log.warn("video_import_recovery_exhausted importId={} stage={} attempts={}",
                    job.getId(), stage, attempts);
            return false;
        }
        jobMapper.incrementAttempt(job.getId());
        return true;
    }

    // ---------------------------------------------------------------- 媒体

    /** 超时 {@code ACQUIRING}：按对象是否已经落盘决定推进还是回退（契约 §7.5）。 */
    private void recoverStaleAcquiring(LocalDateTime staleBefore, int batch, Report report) {
        for (MediaFile media : mediaFileMapper.findStaleByStatuses(
                List.of(MediaImportStatus.ACQUIRING), staleBefore, batch)) {
            if (isDownloadInProgress(media)) {
                // 锁还在 = 进程还活着、正在下载。下载期间 updated_at 不会变化，
                // 只看超时会把健康的长下载判成卡死：重复下载、并白白消耗恢复预算。
                report.skip();
                continue;
            }
            boolean objectPresent = media.getFilePath() != null
                    && !media.getFilePath().isBlank()
                    && minioUtils.objectExists(media.getFilePath());
            if (objectPresent) {
                // 下载与上传已完成、只是进程在写状态前退出：复用已落盘对象，绝不重新下载。
                // 若默认笔记其实也已经完成（状态被回退过），直接收敛到 READY，不重复跑一遍分析。
                if (promoteIfNoteCompleted(media, MediaImportStatus.ACQUIRING)) {
                    report.act("media-acquiring-note-completed", media.getId());
                    continue;
                }
                report.act("media-acquiring-promoted", media.getId());
                acquireService.acquire(media.getId());
                continue;
            }
            if (exhaustedAcquireBudget(media, report)) {
                continue;
            }
            if (mediaFileMapper.casStatus(media.getId(),
                    MediaImportStatus.ACQUIRING, MediaImportStatus.QUEUED) == 0) {
                report.skip();
                continue;
            }
            // 回退到 QUEUED 是中间态：只写媒体行，子项保持非终态直到真正收敛（D-066）。
            report.act("media-acquiring-reset", media.getId());
            dispatchAcquire(media);
        }
    }

    /**
     * 超时 {@code PENDING_DISPATCH/QUEUED/DISPATCH_FAILED} 媒体：重新投递 Unit 获取消息（契约 §7.5）。
     *
     * <p>这三种状态的共同点是“没有消费者在工作”，重投不会造成重复下载：消费者取得执行权必须先把状态
     * CAS 到 {@code ACQUIRING}，而 {@code PENDING_DISPATCH} 与 {@code QUEUED} 是同一段代码路径。
     */
    private void recoverStaleAcquirePending(LocalDateTime staleBefore, int batch, Report report) {
        // 一条查询取三种状态，按行上的状态走同一段逻辑：语句数不再随状态种类增长。
        for (MediaFile media : mediaFileMapper.findStaleByStatuses(
                ACQUIRE_PENDING_STATUSES, staleBefore, batch)) {
            if (exhaustedAcquireBudget(media, report)) {
                continue;
            }
            report.act("media-acquire-redispatched", media.getId());
            dispatchAcquire(media);
        }
    }

    /** 超时 {@code MEDIA_READY}：媒体已入库但默认笔记没投出去，补投（契约 §7.5）。 */
    private void recoverStaleNotePending(LocalDateTime staleBefore, int batch, Report report) {
        for (MediaFile media : mediaFileMapper.findStaleByStatuses(
                List.of(MediaImportStatus.MEDIA_READY), staleBefore, batch)) {
            if (promoteIfNoteCompleted(media, MediaImportStatus.MEDIA_READY)) {
                report.act("media-ready-note-completed", media.getId());
                continue;
            }
            if (noteBudgetExhausted(media, report)) {
                continue;
            }
            report.act("media-ready-note-resubmitted", media.getId());
            acquireService.acquire(media.getId());
        }
    }

    /**
     * 超时 {@code ANALYSIS_QUEUED/ANALYZING}：先读 Checkpoint，再决定推进还是重投（契约 §7.5）。
     *
     * <p>已经有完成结果就直接推进 {@code READY}——分析可能在媒体状态没写对的情况下就完成了
     * （例如生命周期回调的 CAS 与状态推进竞争）。
     *
     * <p>活跃键还在时不能无脑等待：持键进程可能已经死了（崩溃/重启），而键的 TTL 是 6 小时。
     * 判断存活只看一件事——检查点是否仍在推进（{@code video.note.recovery-stale-seconds}，默认 30 分钟）。
     * 超过阈值没有任何检查点写入，就清掉僵尸活跃键并按预算重投；重投成功会写一个同长度的 Redis 标记，
     * 保证同一媒体在阈值窗口内最多只被判死一次，避免把"跑得慢"变成重复分析。
     */
    private void recoverStaleNoteInFlight(LocalDateTime staleBefore, int batch, Report report) {
        for (MediaFile media : mediaFileMapper.findStaleByStatuses(
                NOTE_IN_FLIGHT_STATUSES, staleBefore, batch)) {
            MediaImportStatus status = media.getStatus();
            if (promoteIfNoteCompleted(media, status)) {
                report.act("media-note-completed-recovered", media.getId());
                continue;
            }
            if (isNoteActive(media) && noteProgressFresh(media)) {
                report.skip();
                continue;
            }
            if (isNoteActive(media)) {
                if (!acquireRecoverySlot(media.getId())) {
                    report.skip();
                    continue;
                }
                releaseStaleNoteKey(media, report);
            }
            if (noteBudgetExhausted(media, report)) {
                continue;
            }
            report.act("media-note-redispatched", media.getId());
            acquireService.acquire(media.getId());
        }
    }

    /**
     * 该单元的下载是否仍在进行（存活信号，见 {@link ImportUnitLock}）。
     *
     * <p>查两把锁：{@code (userId, sourceHash)} 的单元锁，以及按内容资产分片的共享内容锁（D-068）。
     * 后者覆盖"另一个用户正在下载同一份内容、本媒体在等它"的情形——那时本进程没有任何副作用在跑，
     * 但媒体确实不该被判定卡死：重置它会消耗重投预算，而它只是在等别人写完字节。
     */
    private boolean isDownloadInProgress(MediaFile media) {
        if (media.getContentAssetId() != null && unitLock.isContentLocked(media.getContentAssetId())) {
            return true;
        }
        if (media.getPlatform() == null || media.getResourceType() == null
                || media.getExternalResourceId() == null || media.getExternalUnitId() == null) {
            return false;
        }
        String sourceKey = String.join(":", media.getPlatform().name(), media.getResourceType(),
                media.getExternalResourceId(), media.getExternalUnitId());
        return unitLock.isUnitLocked(media.getUserId(), VideoImportKeys.sourceHash(sourceKey));
    }

    /**
     * 分析是否仍在推进：最近一次检查点写入距今不超过判死阈值。
     *
     * <p>判死阈值 = max(配置, 2×模型单次超时+余量, 视频时长/2)（D-076 修订）：
     * 一个检查点都没有时以媒体行 {@code updated_at} 为基线（"默认笔记被受理"的时刻），
     * 长视频在第一次上下文落盘前要跑整段 ASR，不能因此被判死。
     */
    private boolean noteProgressFresh(MediaFile media) {
        LocalDateTime latest = latestNoteProgress(media);
        if (latest == null) {
            return false;
        }
        long idleSeconds = java.time.Duration.between(latest, LocalDateTime.now()).getSeconds();
        long thresholdSeconds = budgetService.recoveryStaleThresholdSeconds(
                media.getSourceDurationMs(), noteProperties.getRecoveryStaleSeconds());
        return idleSeconds < thresholdSeconds;
    }

    private LocalDateTime latestNoteProgress(MediaFile media) {
        LocalDateTime checkpointAt = null;
        try {
            checkpointAt = checkpointService.loadLatestProgressAt(media.getId());
        } catch (RuntimeException e) {
            log.warn("video_import_recovery_checkpoint_time_read_failed mediaId={}", media.getId(), e);
        }
        LocalDateTime submittedAt = media.getUpdatedAt();
        if (checkpointAt == null) {
            return submittedAt;
        }
        return submittedAt == null || checkpointAt.isAfter(submittedAt) ? checkpointAt : submittedAt;
    }

    /** 同一媒体在一个静默窗口内最多判死一次，避免重复投递昂贵的分析任务。 */
    private boolean acquireRecoverySlot(Long mediaId) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    VideoImportKeys.attemptsMedia(mediaId), "recovery",
                    java.time.Duration.ofSeconds(noteProperties.getRecoveryStaleSeconds()));
            boolean granted = !Boolean.FALSE.equals(acquired);
            if (!granted) {
                log.info("video_import_recovery_slot_taken mediaId={}", mediaId);
            }
            return granted;
        } catch (RuntimeException e) {
            // Redis 不可用按契约走数据库路径：宁可多试一次，也不要永久停滞。
            log.warn("video_import_recovery_slot_unavailable mediaId={}", mediaId, e);
            return true;
        }
    }

    private void releaseStaleNoteKey(MediaFile media, Report report) {
        try {
            if (analysisDispatchService.releaseStaleActiveKey(
                    media.getId(), VideoNoteProfile.GOAL, VideoNoteProfile.MODE)) {
                report.act("media-note-stale-key-released", media.getId());
            }
        } catch (RuntimeException e) {
            log.warn("video_import_recovery_release_key_failed mediaId={}", media.getId(), e);
        }
    }

    /** 悬挂子项：媒体行已经不存在，子项永远等不到状态推进，落 {@code MEDIA_DELETED} 并重算父任务。 */
    private void failOrphanItems(int batch, Report report) {
        List<Long> affected = new ArrayList<>();
        for (VideoImportItem item : itemMapper.findNonTerminal(batch)) {
            if (mediaFileMapper.selectById(item.getMediaId()) != null) {
                continue;
            }
            if (itemMapper.casItemStatus(item.getImportId(), item.getMediaId(), item.getItemStatus(),
                    MediaImportStatus.FAILED, false,
                    VideoImportErrorCode.MEDIA_DELETED.name()) == 0) {
                continue;
            }
            report.act("item-media-deleted", item.getMediaId());
            affected.add(item.getImportId());
        }
        affected.stream().distinct().forEach(aggregator::recompute);
    }

    /** 父表对账：非终态重算计数与状态，终态与子项矛盾的行按子项收敛（契约 §7.5 / D-052）。 */
    private void reconcileParents(LocalDateTime staleBefore, int batch, Report report) {
        for (VideoImportJob job : jobMapper.findNonTerminalStale(staleBefore, batch)) {
            report.act("parent-reconciled", job.getId());
            aggregator.recompute(job.getId());
        }
        for (VideoImportJob job : jobMapper.findTerminalContradictions(batch)) {
            report.act("parent-contradiction-reconciled", job.getId());
            aggregator.recompute(job.getId());
        }
    }

    // ---------------------------------------------------------------- 公共动作

    /**
     * 重新投递一个媒体的获取消息。
     *
     * <p>消息只携带 {@code mediaId} 与链路 ID，因此这里不需要父任务上下文；父任务由消费者在状态推进后
     * 通过子项表反查。{@code importId} 只用于把子项同步到 {@code QUEUED}，取该媒体任一子项所属任务即可。
     */
    private void dispatchAcquire(MediaFile media) {
        List<VideoImportItem> items = itemMapper.findByMediaId(media.getId());
        Long importId = items.isEmpty() ? null : items.get(0).getImportId();
        String traceId = items.isEmpty() ? "recovery-" + media.getId()
                : traceIdOf(items.get(0).getImportId(), media.getId());
        unitDispatcher.dispatch(importId, media.getId(), traceId);
    }

    private String traceIdOf(Long importId, Long mediaId) {
        VideoImportJob job = importId == null ? null : jobMapper.selectById(importId);
        return job == null || job.getTraceId() == null ? "recovery-" + mediaId : job.getTraceId();
    }

    /** 自动重投预算是否已经耗尽；耗尽时转成“可重试失败”。 */
    private boolean exhaustedAcquireBudget(MediaFile media, Report report) {
        int attempts = media.getAcquireAttemptCount() == null ? 0 : media.getAcquireAttemptCount();
        if (attempts < properties.getMaxAttempts()) {
            // 本次恢复重投也计入预算：消息反复丢失时不能无限重投。
            mediaFileMapper.incrementAcquireAttempt(media.getId());
            return false;
        }
        MediaImportStatus status = media.getStatus();
        mediaFileMapper.casStatus(media.getId(), status, MediaImportStatus.FAILED);
        mediaFileMapper.markAcquireFailed(media.getId(), MediaImportStatus.FAILED,
                MediaImportStatus.FAILED, true,
                VideoImportErrorCode.MEDIA_ACQUIRE_FAILED.name(),
                VideoImportErrorCode.MEDIA_ACQUIRE_FAILED.message());
        itemMapper.updatePendingToTerminalByMediaId(media.getId(), MediaImportStatus.FAILED, true,
                VideoImportErrorCode.MEDIA_ACQUIRE_FAILED.name());
        aggregator.recomputeForMedia(media.getId());
        report.act("media-recovery-exhausted", media.getId());
        log.warn("video_import_recovery_acquire_exhausted mediaId={} status={} attempts={}",
                media.getId(), status, attempts);
        return true;
    }

    /** 默认笔记的自动重投预算；耗尽后转成可重试失败，避免后台容量长期不足时无限重投。 */
    private boolean noteBudgetExhausted(MediaFile media, Report report) {
        int attempts = media.getNoteAttemptCount() == null ? 0 : media.getNoteAttemptCount();
        if (attempts < noteProperties.getMaxAttempts()) {
            return false;
        }
        MediaImportStatus status = media.getStatus();
        mediaFileMapper.markNoteFailed(media.getId(), status, MediaImportStatus.FAILED,
                VideoNoteProfile.VERSION, true,
                VideoImportErrorCode.NOTE_PROCESSING_FAILED.name(),
                VideoImportErrorCode.NOTE_PROCESSING_FAILED.message());
        itemMapper.updatePendingToTerminalByMediaId(media.getId(), MediaImportStatus.FAILED, true,
                VideoImportErrorCode.NOTE_PROCESSING_FAILED.name());
        aggregator.recomputeForMedia(media.getId());
        report.act("media-note-recovery-exhausted", media.getId());
        log.warn("video_import_recovery_note_exhausted mediaId={} status={} attempts={}",
                media.getId(), status, attempts);
        return true;
    }

    /**
     * Checkpoint 里已有 {@code VIDEO_NOTE_V1} 完成结果时，把媒体推进到 {@code READY}。
     *
     * <p>与正常完成回调共用同一条完成边界（D-069）：笔记有结果还不够，检索索引必须就绪。
     * 索引还补不出来时返回 {@code false}（本轮不推进），下一轮扫描再试。
     */
    private boolean promoteIfNoteCompleted(MediaFile media, MediaImportStatus expected) {
        AgentState state = loadCompletedNoteState(media.getId());
        if (state == null) {
            return false;
        }
        if (!mediaIndexService.ensureIndexed(media.getId())) {
            log.warn("video_import_recovery_index_pending mediaId={}", media.getId());
            return false;
        }
        if (mediaFileMapper.casStatus(media.getId(), expected, MediaImportStatus.READY) == 0) {
            return false;
        }
        // 与正常完成回调（VideoImportNoteLifecycle）共用同一条口径（D-104）：
        // Critic 未通过也放行到 READY，但不能清掉警告痕迹。
        boolean verified = state.critique() == null || state.critique().passed();
        if (verified) {
            mediaFileMapper.markNoteCompleted(media.getId(),
                    MediaImportStatus.READY, MediaImportStatus.READY, VideoNoteProfile.VERSION);
        } else {
            mediaFileMapper.markNoteCompletedWithWarning(media.getId(),
                    MediaImportStatus.READY, MediaImportStatus.READY, VideoNoteProfile.VERSION,
                    VideoImportErrorCode.NOTE_UNVERIFIED_EVIDENCE.name(),
                    VideoImportErrorCode.NOTE_UNVERIFIED_EVIDENCE.message());
        }
        itemMapper.updatePendingToTerminalByMediaId(media.getId(), MediaImportStatus.READY, false, null);
        invalidateMediaList(media.getUserId());
        aggregator.recomputeForMedia(media.getId());
        log.info("video_import_note_completed_by_recovery mediaId={} from={} verified={}",
                media.getId(), expected, verified);
        return true;
    }

    /** 结果真源是 Checkpoint；只有真正写出了结果才算完成，同时带回 Critic 校验状态供上面分支使用。 */
    private AgentState loadCompletedNoteState(Long mediaId) {
        try {
            var state = checkpointService.loadResult(mediaId, VideoNoteProfile.GOAL, VideoNoteProfile.MODE);
            return state != null && state.result() != null ? state : null;
        } catch (RuntimeException e) {
            log.warn("video_import_recovery_checkpoint_read_failed mediaId={}", mediaId, e);
            return null;
        }
    }

    /** 活跃键在说明分析任务确实在路上，本轮不重投。 */
    private boolean isNoteActive(MediaFile media) {
        try {
            return analysisDispatchService.isActive(media.getId(), VideoNoteProfile.GOAL, VideoNoteProfile.MODE);
        } catch (RuntimeException e) {
            log.warn("video_import_recovery_active_check_failed mediaId={}", media.getId(), e);
            return false;
        }
    }

    private void invalidateMediaList(Long userId) {
        try {
            mediaService.invalidateUserList(userId);
        } catch (RuntimeException e) {
            log.warn("video_import_recovery_list_invalidation_failed userId={}", userId, e);
        }
    }

    /** 阶段边界：一个阶段整体失败不得影响后续阶段。 */
    private void phase(String name, Report report, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            report.failed++;
            log.error("video_import_recovery_phase_failed phase={}", name, e);
        }
    }

    /** 单轮扫描的动作计数，用于在不必要时保持静默日志。 */
    private static final class Report {

        private int acted;
        private int skipped;
        private int failed;
        private final StringBuilder detail = new StringBuilder();

        void act(String action, Long id) {
            acted++;
            if (detail.length() < 400) {
                if (detail.length() > 0) {
                    detail.append(',');
                }
                detail.append(action).append('#').append(id);
            }
        }

        void skip() {
            skipped++;
        }

        String detail() {
            return detail.toString();
        }
    }
}
