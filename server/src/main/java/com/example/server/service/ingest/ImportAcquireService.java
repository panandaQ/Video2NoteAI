package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.BilibiliCredentialService;
import com.example.server.source.VideoSourceException;
import com.example.server.utils.VideoImportKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Unit 获取编排：取得媒体、写入对象存储、推进媒体状态并提交默认视频笔记。
 *
 * <p>状态推进顺序与契约 §7.3 一致：{@code QUEUED → ACQUIRING} CAS 取得执行权 → 外部副作用成功并持久化
 * 产物后才写 {@code MEDIA_READY} → 提交默认笔记 → 推进 {@code ANALYSIS_QUEUED} → 同步子项并重算父任务。
 *
 * <p>失败分流：可重试且未达尝试上限时把媒体放回 {@code QUEUED} 并抛给 MQ 有限重投；不可重试或已达上限写
 * {@code FAILED}。媒体已被用户删除时直接终止，不产生任何副作用。
 */
@Service
public class ImportAcquireService {

    private static final Logger log = LoggerFactory.getLogger(ImportAcquireService.class);

    /** 需要一次 Unit 获取的状态；顺序即 CAS 尝试顺序，只以数据库当前状态为准。 */
    private static final List<MediaImportStatus> CLAIMABLE_STATUSES = List.of(
            MediaImportStatus.QUEUED,
            MediaImportStatus.PENDING_DISPATCH,
            MediaImportStatus.DISPATCH_FAILED);

    private final MediaFileMapper mediaFileMapper;
    private final VideoImportItemMapper itemMapper;
    private final ImportMediaAcquirer acquirer;
    private final VideoNoteTaskPort noteTaskPort;
    private final ImportJobAggregator aggregator;
    private final ImportUnitLock unitLock;
    private final ImportUnitDispatcher unitDispatcher;
    private final VideoImportProperties properties;
    private final ContentAssetService contentAssetService;
    private final ContentArtifactEnrichmentService artifactEnrichment;
    private final BilibiliCredentialService credentialService;

    public ImportAcquireService(MediaFileMapper mediaFileMapper,
                                VideoImportItemMapper itemMapper,
                                ImportMediaAcquirer acquirer,
                                VideoNoteTaskPort noteTaskPort,
                                ImportJobAggregator aggregator,
                                ImportUnitLock unitLock,
                                ImportUnitDispatcher unitDispatcher,
                                VideoImportProperties properties,
                                ContentAssetService contentAssetService,
                                ContentArtifactEnrichmentService artifactEnrichment,
                                BilibiliCredentialService credentialService) {
        this.mediaFileMapper = mediaFileMapper;
        this.itemMapper = itemMapper;
        this.acquirer = acquirer;
        this.noteTaskPort = noteTaskPort;
        this.aggregator = aggregator;
        this.unitLock = unitLock;
        this.unitDispatcher = unitDispatcher;
        this.properties = properties;
        this.contentAssetService = contentAssetService;
        this.artifactEnrichment = artifactEnrichment;
        this.credentialService = credentialService;
    }

    /** 幂等入口：重复消息只有取得执行权的一次会真正下载。 */
    public void acquire(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            // 媒体已被删除：阶段边界终止后续处理，不再产生下载或对象写入。
            log.info("video_import_acquire_media_missing mediaId={}", mediaId);
            return;
        }
        MediaImportStatus status = media.getStatus();
        if (status == null) {
            log.warn("video_import_acquire_status_missing mediaId={}", mediaId);
            return;
        }
        // “已入库”以对象引用为准：重试或恢复扫描可能把业务状态回退到待获取，但对象仍在存储里，
        // 此时绝不能重新下载，只需回到可投递默认笔记的状态。
        boolean storedObject = media.getFilePath() != null && !media.getFilePath().isBlank();
        if (storedObject || status.isMediaStored()) {
            if (!status.isMediaStored()) {
                // 这里同样要容忍过期读值：投递方"先发消息、后 CAS 到 QUEUED"，消费者可能在毫秒之间
                // 读到 PENDING_DISPATCH（共享字节在注册阶段就已挂上），直接返回就要等第二条消息或恢复扫描。
                if (mediaFileMapper.casStatus(mediaId, status, MediaImportStatus.MEDIA_READY) == 0
                        && !promoteFromAnyPendingStatus(mediaId)) {
                    log.warn("video_import_acquire_stored_object_not_promoted mediaId={} status={}",
                            mediaId, status);
                    return;
                }
                log.info("video_import_media_ready_recovered mediaId={} from={}", mediaId, status);
                submitNote(media, MediaImportStatus.MEDIA_READY);
                return;
            }
            log.info("video_import_acquire_skipped_stored mediaId={} status={}", mediaId, status);
            if (status == MediaImportStatus.READY
                    && !VideoNoteProfile.VERSION.equals(media.getNoteProfileVersion())) {
                // V1 媒体再次导入的懒升级（§6.3/D-078）：保持 READY（列表可见、V1 结果可查），
                // 只补充 V2 资产并投递 V2 默认笔记，不重复下载共享视频字节。
                upgradeNoteToV2(media);
                return;
            }
            submitNote(media, status);
            return;
        }
        // 跨用户共享字节（D-068）：内容资产已有可用对象时直接复用引用，一次下载都不发。
        // 放在执行权 CAS 之前：这是纯读判定，命中就完全没有"获取"这回事（AC-06）。
        if (adoptSharedBytes(media)) {
            promoteToMediaReady(media, status, "shared");
            return;
        }
        if (!claimForAcquire(media, status)) {
            // 没拿到执行权有两种原因：其他消费者正在处理，或投递竞态让状态刚被推进。
            // 必须重读一次再决定，否则消息被确认后单元会永久停在 QUEUED。
            MediaFile latest = mediaFileMapper.selectById(mediaId);
            MediaImportStatus latestStatus = latest == null ? null : latest.getStatus();
            if (latestStatus != null && latestStatus.isMediaStored()) {
                submitNote(latest, latestStatus);
                return;
            }
            log.info("video_import_acquire_not_claimed mediaId={} observed={} latest={}",
                    mediaId, status, latestStatus);
            return;
        }

        try (ImportUnitLock.Handle lock = unitLock.tryLockUnit(
                media.getUserId(), VideoImportKeys.sourceHash(sourceKeyOf(media)));
             ImportUnitLock.Handle contentLock = unitLock.tryLockContent(assetIdOf(media))) {
            if (!lock.acquired()) {
                // 同一单元已经有进程在下载（重复投递、或恢复扫描与前台消费者竞争）。
                // 直接返回且不产生任何副作用：持有者会完成下载并从 ACQUIRING 推进状态。
                log.info("video_import_acquire_lock_busy mediaId={}", mediaId);
                return;
            }
            if (!contentLock.acquired()) {
                // 另一个用户正在下载同一份内容（AC-07：只允许一个共享下载）。抢锁失败后**再查一次**
                // 资产：对方可能刚好写完字节，此时直接复用；否则原样返回——媒体停在 ACQUIRING，
                // 而内容锁仍在，恢复扫描按"有人在下载"跳过它，不重置、不消耗重投预算（D-061 语义）。
                MediaFile latest = mediaFileMapper.selectById(mediaId);
                if (latest != null && adoptSharedBytes(latest)) {
                    promoteToMediaReady(latest, MediaImportStatus.ACQUIRING, "shared-after-wait");
                } else {
                    log.info("video_import_content_download_in_progress mediaId={} assetId={}",
                            mediaId, assetIdOf(media));
                }
                return;
            }
            String cookie = credentialService.getCookie(media.getUserId());
            Integer height = media.getRequestedQuality();
            ImportMediaAcquirer.StoredMedia stored = acquirer.acquireAndStore(media, height, cookie);
            mediaFileMapper.markMediaReady(mediaId,
                    MediaImportStatus.ACQUIRING, MediaImportStatus.MEDIA_READY,
                    stored.objectUrl(), stored.contentHash(), stored.sizeBytes(), stored.coverObjectUrl());
            // 子项不跟随中间态（D-066）：媒体状态是执行依据，子项只在 READY/FAILED 时同步。
            log.info("video_import_media_ready mediaId={} contentHash={}",
                    mediaId, stored.contentHash());
            // 本条目已经指向对象之后，才把字节登记为共享资产并广播（D-068）：此刻起别的用户可以
            // 直接复用，而"还在等"的查询不会把本条目误当成等待者。
            contentAssetService.publishBytes(media.getContentAssetId(), stored.contentHash(),
                    stored.objectUrl(), stored.coverObjectUrl(), stored.sizeBytes());
            // 必须用**刚落库的媒体行**投递笔记：上面的 `media` 是下载前读的，`content_hash` 直到
            // markMediaReady 才写进数据库，实体上仍然是 null。把它直接交给 submitNote，会让
            // enrichBeforeNote 的分析输入补充在第一个守卫（contentHash 判空）处静默返回——
            // 字幕与章节都不会落盘，整段分析退化成 ASR（2026-09-22 实测：87 分钟视频白跑 88 片
            // ASR、笔记永久没有章节，见 D-098）。
            MediaFile refreshed = mediaFileMapper.selectById(mediaId);
            if (refreshed == null) {
                log.warn("video_import_media_missing_after_store mediaId={}", mediaId);
                return;
            }
            submitNote(refreshed, MediaImportStatus.MEDIA_READY);
        } catch (VideoSourceException e) {
            boolean retry = recordFailure(media, e);
            if (retry) {
                // 下载已移出消费线程：异常不会再冒泡给 RocketMQ，重投必须显式完成。
                // 用延迟消息保留退避，次数上限由 acquire_attempt_count 与 max-attempts 决定（D-064）。
                unitDispatcher.dispatchDelayed(mediaId, ImportUnitDispatcher.RETRY_DELAY_LEVEL);
            }
        }
    }

    /**
     * 命中共享字节时把内容资产的对象引用挂到当前媒体行。
     *
     * <p>只读判定 + 一条条件 UPDATE，不需要锁：真正的并发收敛点是
     * {@code UPDATE ... WHERE id = ? AND file_path IS NULL}——同一份字节不会被写两次。
     */
    private boolean adoptSharedBytes(MediaFile media) {
        if (media.getFilePath() != null && !media.getFilePath().isBlank()) {
            return false;
        }
        try {
            return contentAssetService.adoptSharedBytes(media, contentAssetService.findByMedia(media));
        } catch (RuntimeException e) {
            // 复用是省一次下载的优化，失败就退回正常下载，不能让整条链路失败。
            log.warn("video_import_shared_bytes_adopt_failed mediaId={}", media.getId(), e);
            return false;
        }
    }

    /** 共享字节命中后的推进：只写状态与笔记投递，不产生下载与对象写入。 */
    private void promoteToMediaReady(MediaFile media, MediaImportStatus expected, String reason) {
        Long mediaId = media.getId();
        if (mediaFileMapper.casStatus(mediaId, expected, MediaImportStatus.MEDIA_READY) == 0
                && !promoteFromAnyPendingStatus(mediaId)) {
            log.info("video_import_shared_bytes_not_promoted mediaId={} expected={} reason={}",
                    mediaId, expected, reason);
            return;
        }
        log.info("video_import_media_ready mediaId={} reason={}", mediaId, reason);
        submitNote(media, MediaImportStatus.MEDIA_READY);
    }

    /**
     * 共享字节的推进必须和执行权 CAS 一样容忍"投递方刚把状态改掉"。
     *
     * <p>实测到的窗口：消费者读到 {@code PENDING_DISPATCH}（此时注册阶段已经挂上共享对象引用），
     * 与此同时投递方把它 CAS 成 {@code QUEUED}——用过期的读值推进必然失败。逐个尝试可获取状态即可，
     * 否则这条消息会白跑一趟，只能等恢复扫描或第二条消息来补。
     */
    private boolean promoteFromAnyPendingStatus(Long mediaId) {
        for (MediaImportStatus candidate : CLAIMABLE_STATUSES) {
            if (mediaFileMapper.casStatus(mediaId, candidate, MediaImportStatus.MEDIA_READY) > 0) {
                return true;
            }
        }
        return false;
    }

    private Long assetIdOf(MediaFile media) {
        return media.getContentAssetId();
    }

    /**
     * 单元身份摘要的输入：与来源 Adapter 的 {@code sourceKey()} 同构。
     *
     * <p>四段身份缺失时返回 {@code null}（锁的守卫会降级放行）：锁只是减少重复下载的优化，
     * 不是正确性来源——正确性永远由 {@code QUEUED → ACQUIRING} 的数据库 CAS 与状态条件更新保证。
     */
    private String sourceKeyOf(MediaFile media) {
        if (media.getPlatform() == null || media.getResourceType() == null
                || media.getExternalResourceId() == null || media.getExternalUnitId() == null) {
            return null;
        }
        return String.join(":", media.getPlatform().name(), media.getResourceType(),
                media.getExternalResourceId(), media.getExternalUnitId());
    }

    /**
     * 取得获取执行权。
     *
     * <p>投递顺序是“先发消息、后把状态 CAS 到 {@code QUEUED}”，因此消费者可能在状态推进之前就拿到消息：
     * 它读到的 {@code PENDING_DISPATCH} 会在毫秒后变成 {@code QUEUED}，用过期的读值做 CAS 必然失败。
     * 这里对所有“需要获取”的状态依次尝试 CAS，只以数据库当前状态为准。
     *
     * <p>{@code FAILED} 且 {@code acquire_retryable=1} 的媒体也允许重新获取：那是“可重试错误耗尽
     * 自动重投”的终态，不是确定性失败。用户重新提交同一链接或显式重试时，媒体行会被复用，
     * 此时必须真的再试一次，否则一次网络抖动会让这个视频永久无法导入（D-053）。
     *
     * @return 是否取得执行权；确定性失败（不可重试）的 {@code FAILED} 不会被重新激活
     */
    private boolean claimForAcquire(MediaFile media, MediaImportStatus observed) {
        Long mediaId = media.getId();
        if (observed.needsAcquire()
                && mediaFileMapper.casStatus(mediaId, observed, MediaImportStatus.ACQUIRING) > 0) {
            return true;
        }
        for (MediaImportStatus candidate : CLAIMABLE_STATUSES) {
            if (candidate != observed
                    && mediaFileMapper.casStatus(mediaId, candidate, MediaImportStatus.ACQUIRING) > 0) {
                return true;
            }
        }
        if (observed == MediaImportStatus.FAILED
                && Boolean.TRUE.equals(media.getAcquireRetryable())
                && mediaFileMapper.casStatus(mediaId, MediaImportStatus.FAILED,
                        MediaImportStatus.ACQUIRING) > 0) {
            log.info("video_import_acquire_retry_after_failure mediaId={}", mediaId);
            return true;
        }
        return false;
    }

    /**
     * 提交默认视频笔记并推进业务状态。
     *
     * <p>投递前必须先完成分析输入资产补充（P0/P1：字幕与章节是 V2 笔记的输入来源）：
     * 补充失败保持 {@code MEDIA_READY} 并记录可重试延迟标记，由恢复扫描重试，禁止把
     * "有章节却无 chapters.json"的媒体推进到分析（计划 §4.4/§6.1）。
     *
     * <p>后台容量不足时同样保持 {@code MEDIA_READY}（S3）；绝不复用交互式 429 语义把
     * 自动任务判为永久失败。
     */
    private void submitNote(MediaFile media, MediaImportStatus expectedStatus) {
        if (!enrichBeforeNote(media, expectedStatus)) {
            return;
        }
        Long mediaId = media.getId();
        AnalysisDispatchService.SubmissionResult result = noteTaskPort.submitDefaultNote(mediaId);
        switch (result) {
            case ACCEPTED -> {
                mediaFileMapper.casStatus(mediaId,
                        MediaImportStatus.MEDIA_READY, MediaImportStatus.ANALYSIS_QUEUED);
                // 子项不跟随中间态（D-066）：ANALYSIS_QUEUED 只写在媒体行上。
            }
            case DUPLICATE -> log.info("video_import_note_already_active mediaId={}", mediaId);
            case RATE_LIMITED -> {
                mediaFileMapper.markNoteDeferred(mediaId, expectedStatus,
                        VideoNoteProfile.VERSION,
                        VideoImportErrorCode.NOTE_DISPATCH_DEFERRED.name(),
                        VideoImportErrorCode.NOTE_DISPATCH_DEFERRED.message());
                log.warn("video_import_note_deferred mediaId={}", mediaId);
            }
            case FAILED -> {
                mediaFileMapper.markNoteFailed(mediaId, expectedStatus, MediaImportStatus.FAILED,
                        VideoNoteProfile.VERSION, true,
                        VideoImportErrorCode.NOTE_PROCESSING_FAILED.name(),
                        VideoImportErrorCode.NOTE_PROCESSING_FAILED.message());
                itemMapper.updatePendingToTerminalByMediaId(mediaId, MediaImportStatus.FAILED, true,
                        VideoImportErrorCode.NOTE_PROCESSING_FAILED.name());
                aggregator.recomputeForMedia(mediaId);
                log.error("video_import_note_dispatch_failed mediaId={}", mediaId);
            }
        }
    }

    /**
     * 提交默认笔记前的分析输入资产补充闸门（计划 §4.4 交付约束 5）：
     * 新下载、已有对象、共享字节三条路径都必须经过它。
     *
     * <p>闸门对**所有**投递路径生效，而不是只在 {@code MEDIA_READY} 那一条：再次导入一个已经
     * {@code READY} 的媒体（重建笔记、幂等重投、D-078 之外的重复提交）走的是终态入参，
     * 早先的写法直接绕过了补充，结果是在没有 manifest 的前提下重跑一遍整段 ASR（D-098）。
     *
     * @return 只有 manifest 已就绪或本次补充完成才返回 {@code true}；锁忙和失败均返回
     *         {@code false}，调用方不得投递笔记
     */
    private boolean enrichBeforeNote(MediaFile media, MediaImportStatus expectedStatus) {
        try {
            ContentArtifactEnrichmentService.EnrichmentResult result =
                    artifactEnrichment.ensureArtifacts(media);
            if (result != ContentArtifactEnrichmentService.EnrichmentResult.READY) {
                deferArtifactEnrichment(media, expectedStatus);
                return false;
            }
            return true;
        } catch (ContentArtifactEnrichmentService.ArtifactEnrichmentException e) {
            deferArtifactEnrichment(media, expectedStatus);
            return false;
        }
    }

    private void deferArtifactEnrichment(MediaFile media, MediaImportStatus expectedStatus) {
        if (expectedStatus == MediaImportStatus.MEDIA_READY) {
            // 不增加 note_attempt_count：恢复扫描稍后重新调用 acquire，并再次经过本闸门。
            mediaFileMapper.markNoteDeferred(media.getId(), MediaImportStatus.MEDIA_READY,
                    VideoNoteProfile.VERSION,
                    VideoImportErrorCode.ARTIFACT_ENRICHMENT_FAILED.name(),
                    VideoImportErrorCode.ARTIFACT_ENRICHMENT_FAILED.message());
            log.warn("video_import_artifact_enrichment_deferred mediaId={}", media.getId());
        } else {
            // 已 READY 的媒体没有可回退的中间态：保持 READY（旧笔记仍然可用），
            // 放弃本次重新分析，等下一次显式触发；不能在缺少 manifest 时重跑。
            log.warn("video_import_artifact_enrichment_not_ready mediaId={} expectedStatus={}",
                    media.getId(), expectedStatus);
        }
    }

    /**
     * V1 → V2 懒升级（D-078）：媒体保持 {@code READY}，升级失败只记日志，V1 结果不受影响，
     * 下次再导入或显式操作时重试；不重复下载（本路径只在对象已入库时到达）。
     */
    private void upgradeNoteToV2(MediaFile media) {
        try {
            if (artifactEnrichment.ensureArtifacts(media)
                    != ContentArtifactEnrichmentService.EnrichmentResult.READY) {
                log.info("video_import_note_v2_upgrade_enrich_in_progress mediaId={}", media.getId());
                return;
            }
        } catch (ContentArtifactEnrichmentService.ArtifactEnrichmentException e) {
            log.warn("video_import_note_v2_upgrade_enrich_failed mediaId={}", media.getId());
            return;
        }
        AnalysisDispatchService.SubmissionResult result = noteTaskPort.submitDefaultNote(media.getId());
        log.info("video_import_note_v2_upgrade mediaId={} result={}", media.getId(), result);
    }

    /** @return 是否应交给 MQ 重投 */
    private boolean recordFailure(MediaFile media, VideoSourceException error) {        int attempts = media.getAcquireAttemptCount() == null ? 0 : media.getAcquireAttemptCount();
        boolean retryable = error.retryable() && attempts + 1 < properties.getMaxAttempts();
        MediaImportStatus next = retryable ? MediaImportStatus.QUEUED : MediaImportStatus.FAILED;
        // 落库的可重试标记取错误码本身的可重试性，而不是"还剩几次 MQ 重投"：
        // 状态 FAILED 表示自动处理已经停止，标记为 true 表示用户仍可显式重试（D-053）。
        mediaFileMapper.markAcquireFailed(media.getId(),
                MediaImportStatus.ACQUIRING, next, error.retryable(),
                error.errorCode().name(), error.errorCode().message());
        // 只有落 FAILED 才同步子项：可重试失败会把媒体放回 QUEUED，那是中间态，子项保持 PENDING_DISPATCH，
        // 重投成功后会随终态一次写清（D-066）。
        if (next == MediaImportStatus.FAILED) {
            itemMapper.updatePendingToTerminalByMediaId(media.getId(), MediaImportStatus.FAILED,
                    error.retryable(), error.errorCode().name());
            aggregator.recomputeForMedia(media.getId());
        }
        log.warn("video_import_acquire_failed mediaId={} errorCode={} retryable={} attempt={} mqRetry={}",
                media.getId(), error.errorCode().name(), error.retryable(), attempts + 1, retryable);
        return retryable;
    }
}
