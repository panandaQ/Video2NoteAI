package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.config.VideoImportProperties;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.dto.VideoImportSubmissionResponse;
import com.example.server.entity.VideoImportJob;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.service.BilibiliCredentialService;
import com.example.server.source.ImportTargetType;
import com.example.server.utils.VideoImportKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 创建或复用 URL 导入父任务，并把任务交给 MQ。
 *
 * <p>请求线程只做三件事：规范化 URL、取得活跃父任务、投递解析消息。它不访问视频平台、不下载媒体，
 * 也不等待合集枚举（契约 §7.1）。投递与状态推进细节在 {@link ImportResolveDispatcher}，与用户重试共用。
 *
 * <p>幂等分两层：Redis 请求映射负责快速返回，{@code active_request_key} 唯一约束负责最终正确性。
 * 唯一键竞争按“并发复用路径”处理，不记为系统异常。
 */
@Service
public class ImportJobService {

    private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);

    private final VideoImportJobMapper jobMapper;
    private final ImportUrlNormalizer urlNormalizer;
    private final ImportRequestCache requestCache;
    private final ImportJobQuota quota;
    private final ImportResolveDispatcher resolveDispatcher;
    private final VideoImportProperties properties;
    private final BilibiliCredentialService credentialService;

    public ImportJobService(VideoImportJobMapper jobMapper,
                            ImportUrlNormalizer urlNormalizer,
                            ImportRequestCache requestCache,
                            ImportJobQuota quota,
                            ImportResolveDispatcher resolveDispatcher,
                            VideoImportProperties properties,
                            BilibiliCredentialService credentialService) {
        this.jobMapper = jobMapper;
        this.urlNormalizer = urlNormalizer;
        this.requestCache = requestCache;
        this.quota = quota;
        this.resolveDispatcher = resolveDispatcher;
        this.properties = properties;
        this.credentialService = credentialService;
    }

    /**
     * 创建或复用导入任务。
     *
     * @param quality 可选清晰度（高度像素），可空 = 默认
     * @param clientIp 来源地址，用于 IP 维度限流；未知时传 {@code null}
     * @return 受理结果；{@code reused=true} 表示命中了同用户同 URL 的活跃任务，不重复投递解析
     * @throws BusinessException 400 参数非法、429 超过提交频率或新建限额、503 MQ 明确投递失败
     */
    public VideoImportSubmissionResponse submit(Long userId, String rawUrl, Integer quality, String clientIp) {
        requireAcceptableUrl(rawUrl);
        requireAcceptableQuality(quality);
        requireCookie(userId);
        String normalizedUrl = urlNormalizer.normalize(rawUrl);
        String requestHash = VideoImportKeys.requestHash(normalizedUrl);
        String activeRequestKey = VideoImportKeys.activeRequestKey(userId, requestHash);

        // 每次有效请求先削峰，再访问请求缓存或 MySQL；这与“新建任务”配额是两套独立的桶。
        quota.requireSubmissionQuota(userId, clientIp);

        VideoImportJob active = findActiveJob(userId, requestHash, activeRequestKey);
        if (active != null) {
            return VideoImportSubmissionResponse.of(active, true);
        }

        // 重复请求不消耗新建配额：只有走到这里才需要检查限额。
        quota.requireNewJobQuota(userId, clientIp);

        VideoImportJob job = createJob(userId, normalizedUrl, quality, requestHash, activeRequestKey);
        if (job == null) {
            // 唯一键竞争：另一个并发请求刚刚建好同一个活跃任务，复用它的 importId。
            VideoImportJob winner = jobMapper.findByActiveRequestKey(activeRequestKey);
            if (winner == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "导入任务正在创建，请稍后重试");
            }
            requestCache.remember(userId, requestHash, winner.getId());
            return VideoImportSubmissionResponse.of(winner, true);
        }

        resolveDispatcher.dispatch(job);
        VideoImportJob current = jobMapper.findOwnedById(job.getId(), userId);
        return VideoImportSubmissionResponse.of(current == null ? job : current, false);
    }

    /** 默认清晰度入口：沿用 480P，兼容旧调用方。 */
    public VideoImportSubmissionResponse submit(Long userId, String rawUrl, String clientIp) {
        return submit(userId, rawUrl, null, clientIp);
    }

    /**
     * 链接长度上限来自配置（契约 §12：{@code video.import.max-url-length}）。
     *
     * <p>DTO 上的 {@code @Size(max = 2048)} 是数据库列宽对应的物理上限；可运营调整的业务上限在这里判定，
     * 两处都保留是为了让"改配置即生效"与"不会写出超长列"同时成立。
     */
    private void requireAcceptableUrl(String rawUrl) {
        if (rawUrl == null) {
            return;
        }
        int maxLength = properties.getMaxUrlLength();
        if (rawUrl.length() > maxLength) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "视频链接不能超过 " + maxLength + " 个字符");
        }
    }

    /** 清晰度白名单校验：可空 = 默认；非法值在请求受理前拒绝，不让脏值进入异步链路。 */
    private void requireAcceptableQuality(Integer quality) {
        if (quality == null) {
            return;
        }
        if (!VideoImportProperties.SUPPORTED_QUALITIES.contains(quality)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "不支持的清晰度，可选 360/480/720/1080");
        }
    }

    /** 强制登录：未保存 B 站 Cookie 时在受理阶段直接拒绝，不创建任务、不投递 MQ。 */
    private void requireCookie(Long userId) {
        if (!credentialService.hasCookie(userId)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "请先在设置页保存 B 站 Cookie 后再导入");
        }
    }

    private VideoImportJob findActiveJob(Long userId, String requestHash, String activeRequestKey) {        Long cachedImportId = requestCache.findImportId(userId, requestHash);
        if (cachedImportId != null) {
            VideoImportJob cached = jobMapper.findOwnedById(cachedImportId, userId);
            if (cached != null && !cached.getStatus().isTerminal()) {
                return cached;
            }
            // 终态后允许重新提交同一 URL，以发现合集新增单元。
            requestCache.forget(userId, requestHash);
        }
        VideoImportJob active = jobMapper.findByActiveRequestKey(activeRequestKey);
        if (active != null && !active.getStatus().isTerminal()) {
            requestCache.remember(userId, requestHash, active.getId());
            return active;
        }
        return null;
    }

    /** @return 新建的任务；唯一键竞争时返回 {@code null} 交给调用方复用已有任务 */
    private VideoImportJob createJob(Long userId, String normalizedUrl, Integer quality,
                                     String requestHash, String activeRequestKey) {
        VideoImportJob job = new VideoImportJob();
        job.setUserId(userId);
        job.setOriginalUrl(normalizedUrl);
        job.setRequestHash(requestHash);
        job.setActiveRequestKey(activeRequestKey);
        job.setTargetType(ImportTargetType.DETECTING);
        job.setRequestedQuality(quality);
        job.setStatus(VideoImportJobStatus.PENDING_DISPATCH);
        job.setTotalCount(0);
        job.setReusedCount(0);
        job.setCompletedCount(0);
        job.setFailedCount(0);
        job.setAttemptCount(0);
        job.setRetryable(false);
        // traceId 在创建时生成并持久化：重试复用同一值，API 不回传。
        job.setTraceId(UUID.randomUUID().toString());
        try {
            jobMapper.insert(job);
            return job;
        } catch (DuplicateKeyException e) {
            log.info("video_import_job_duplicate userId={} requestHash={}", userId, requestHash);
            return null;
        }
    }
}
