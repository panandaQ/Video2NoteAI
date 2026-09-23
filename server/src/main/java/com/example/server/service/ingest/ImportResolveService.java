package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.service.BilibiliCredentialService;
import com.example.server.source.ImportTargetType;
import com.example.server.source.SourceAdapterRegistry;
import com.example.server.source.VideoImportPlan;
import com.example.server.source.VideoSourceAdapter;
import com.example.server.source.VideoSourceException;
import com.example.server.source.VideoSourceUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * URL 解析与展开的编排。
 *
 * <p>执行权来自数据库状态 CAS（{@code QUEUED → RESOLVING}），不是消息本身：同一父任务的重复解析消息
 * 只有第一个能进入工作流。解析失败按可重试性分流——可重试放回 {@code QUEUED} 交给 MQ 有限重投，
 * 不可重试把父任务置为 {@code FAILED} 并保留受控错误文案。
 *
 * <p>S1 只处理单 P；多单元展开由 Adapter 在 S2 提供，本类的上限校验与父任务聚合逻辑已按最终形态实现。
 */
@Service
public class ImportResolveService {

    private static final Logger log = LoggerFactory.getLogger(ImportResolveService.class);

    private final VideoImportJobMapper jobMapper;
    private final SourceAdapterRegistry adapterRegistry;
    private final ImportMediaRegistrar mediaRegistrar;
    private final ImportUnitDispatcher unitDispatcher;
    private final ImportJobAggregator aggregator;
    private final VideoImportProperties properties;
    private final ImportTerminalEventPublisher terminalPublisher;
    private final BilibiliCredentialService credentialService;

    public ImportResolveService(VideoImportJobMapper jobMapper,
                               SourceAdapterRegistry adapterRegistry,
                               ImportMediaRegistrar mediaRegistrar,
                               ImportUnitDispatcher unitDispatcher,
                               ImportJobAggregator aggregator,
                               VideoImportProperties properties,
                               ImportTerminalEventPublisher terminalPublisher,
                               BilibiliCredentialService credentialService) {
        this.jobMapper = jobMapper;
        this.adapterRegistry = adapterRegistry;
        this.mediaRegistrar = mediaRegistrar;
        this.unitDispatcher = unitDispatcher;
        this.aggregator = aggregator;
        this.properties = properties;
        this.terminalPublisher = terminalPublisher;
        this.credentialService = credentialService;
    }

    /** 幂等入口：重复调用时只有取得执行权的一次会真正解析。 */
    public void resolve(Long importId) {
        VideoImportJob job = jobMapper.selectById(importId);
        if (job == null) {
            log.warn("video_import_resolve_job_missing importId={}", importId);
            return;
        }
        if (job.getStatus().isTerminal()) {
            log.info("video_import_resolve_skipped importId={} status={}", importId, job.getStatus());
            return;
        }
        if (jobMapper.casStatus(importId, VideoImportJobStatus.QUEUED, VideoImportJobStatus.RESOLVING) == 0) {
            log.info("video_import_resolve_not_claimed importId={} status={}", importId, job.getStatus());
            return;
        }

        try {
            VideoImportPlan plan = resolvePlan(job);
            List<VideoSourceUnit> units = validateAndDeduplicate(plan);
            persistResolvedPlan(job, plan);
            List<ImportMediaRegistrar.RegisteredUnit> registered = mediaRegistrar.register(job, units);
            for (ImportMediaRegistrar.RegisteredUnit unit : registered) {
                dispatchIfNeeded(job, unit);
            }
            aggregator.recompute(importId);
        } catch (VideoSourceException e) {
            if (e.retryable()) {
                // 放回 QUEUED，交 RocketMQ 有限重投；重投后重新走 CAS 取得执行权。
                jobMapper.casStatus(importId, VideoImportJobStatus.RESOLVING, VideoImportJobStatus.QUEUED);
                throw e;
            }
            failJob(importId, e);
        } catch (RuntimeException e) {
            jobMapper.casStatus(importId, VideoImportJobStatus.RESOLVING, VideoImportJobStatus.QUEUED);
            throw e;
        }
    }

    private VideoImportPlan resolvePlan(VideoImportJob job) {
        URI uri;
        try {
            uri = URI.create(job.getOriginalUrl());
        } catch (IllegalArgumentException e) {
            throw new VideoSourceException(VideoImportErrorCode.SOURCE_METADATA_INVALID,
                    "已保存的链接无法解析", e);
        }
        VideoSourceAdapter adapter = adapterRegistry.requireAdapter(uri);
        // 强制登录：无 Cookie 或登录态失效一律拒绝，绝不让请求落回匿名/全局回退。
        String cookie = credentialService.getCookie(job.getUserId());
        if (cookie == null || cookie.isBlank()) {
            throw new VideoSourceException(VideoImportErrorCode.BILIBILI_COOKIE_REQUIRED,
                    VideoImportErrorCode.BILIBILI_COOKIE_REQUIRED.message());
        }
        if (!adapter.isCookieValid(cookie)) {
            throw new VideoSourceException(VideoImportErrorCode.BILIBILI_COOKIE_EXPIRED,
                    VideoImportErrorCode.BILIBILI_COOKIE_EXPIRED.message());
        }
        return adapter.resolve(uri, cookie);
    }

    /**
     * 校验解析结果：非空、身份完整、按 {@code sourceKey} 去重、不超过配置上限。
     *
     * <p>整个校验必须在登记事务之前完成，否则会出现“部分子项已写入”的不可重试失败。
     */
    private List<VideoSourceUnit> validateAndDeduplicate(VideoImportPlan plan) {
        if (plan == null || plan.targetType() == ImportTargetType.DETECTING
                || plan.units() == null || plan.units().isEmpty()) {
            throw new VideoSourceException(VideoImportErrorCode.SOURCE_METADATA_INVALID,
                    "平台未返回可播放单元");
        }
        Map<String, VideoSourceUnit> unique = new LinkedHashMap<>();
        for (VideoSourceUnit unit : plan.units()) {
            requireCompleteIdentity(unit);
            unique.putIfAbsent(unit.sourceKey(), unit);
        }
        if (unique.size() > properties.getMaxItems()) {
            throw new VideoSourceException(VideoImportErrorCode.COLLECTION_TOO_LARGE,
                    "合集单元数超过上限 " + properties.getMaxItems());
        }
        return List.copyOf(unique.values());
    }

    /**
     * 回写解析结果：目标类型、平台与容器展示快照。
     *
     * <p>必须在登记事务之前完成：查询接口要能在整个处理过程中回答“这次提交被识别成了什么”，
     * 而不是等到终态才有值。平台与容器只做展示，不参与媒体唯一键。
     *
     * <p>只写数据库，不修改传入实体：本方法的调用方持有的是进入工作流时的快照，
     * 就地改写会让后续基于该快照的判断和比较变得不可预期。
     */
    private void persistResolvedPlan(VideoImportJob job, VideoImportPlan plan) {
        try {
            jobMapper.updateResolvedPlan(job.getId(), plan.targetType().name(),
                    plan.platform() == null ? null : plan.platform().name(),
                    plan.containerId(), plan.containerTitle());
        } catch (RuntimeException e) {
            log.warn("video_import_resolved_plan_write_failed importId={}", job.getId(), e);
        }
    }

    private void requireCompleteIdentity(VideoSourceUnit unit) {        if (unit == null || unit.platform() == null
                || isBlank(unit.resourceType())
                || isBlank(unit.externalResourceId())
                || isBlank(unit.externalUnitId())
                || isBlank(unit.canonicalUrl())) {
            throw new VideoSourceException(VideoImportErrorCode.SOURCE_METADATA_INVALID,
                    "平台返回的单元缺少稳定身份");
        }
    }

    /**
     * 已入库的媒体不再重复下载；失败的媒体按可重试性决定是否重投。
     *
     * <p>{@code READY} 例外（D-078 懒升级）：再次导入 READY 媒体时仍投递获取消息，
     * 由 {@code ImportAcquireService} 判定是否需要 V1→V2 升级（版本已是最新则投递默认笔记，
     * 分发层按已完成结果去重，无副作用）。
     */
    private void dispatchIfNeeded(VideoImportJob job, ImportMediaRegistrar.RegisteredUnit unit) {
        if (unit.mediaStatus().isMediaStored() && unit.mediaStatus() != MediaImportStatus.READY) {
            log.info("video_import_unit_already_stored importId={} mediaId={} status={}",
                    job.getId(), unit.mediaId(), unit.mediaStatus());
            return;
        }
        unitDispatcher.dispatch(job.getId(), unit.mediaId(), job.getTraceId());
    }

    private void failJob(Long importId, VideoSourceException error) {
        // 可重试性必须原样落库：它决定用户能否通过 POST /video-imports/{importId}/retry 再试一次。
        // 早先写死 false，使可重试的解析失败在 MQ 重投耗尽后彻底没有出口（D-053）。
        jobMapper.updateError(importId, error.errorCode().name(), error.errorCode().message(),
                error.retryable());
        // 只有这次 CAS 真的把父任务推进到 FAILED 才发布终态：重复消息里失败的一侧不该再发一次通知。
        // 文案取错误码字典里的受控文本（不是第三方原始错误）。
        if (jobMapper.casStatusAndReleaseActiveKey(
                importId, VideoImportJobStatus.RESOLVING, VideoImportJobStatus.FAILED) > 0) {
            terminalPublisher.publishFailed(importId, error.errorCode().message());
        }
        log.error("video_import_resolve_failed importId={} errorCode={} retryable={}",
                importId, error.errorCode().name(), error.retryable());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
