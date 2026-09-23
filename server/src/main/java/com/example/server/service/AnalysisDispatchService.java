package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.config.VideoNoteProperties;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class AnalysisDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatchService.class);
    private static final int USER_REQUESTS_PER_MINUTE = 5;
    private static final int GLOBAL_REQUESTS_PER_MINUTE = 30;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

    private final AiService aiService;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedissonClient redissonClient;
    private final TaskEventService taskEventService;
    private final VideoNoteProperties noteProperties;
    private final String analysisTopic;

    public AnalysisDispatchService(AiService aiService,
                                   MediaService mediaService,
                                   StringRedisTemplate redisTemplate,
                                   RocketMQTemplate rocketMQTemplate,
                                   RedissonClient redissonClient,
                                   TaskEventService taskEventService,
                                   VideoNoteProperties noteProperties,
                                   @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                   String analysisTopic) {
        this.aiService = aiService;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redissonClient = redissonClient;
        this.taskEventService = taskEventService;
        this.noteProperties = noteProperties;
        this.analysisTopic = analysisTopic;
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 提交。 */
    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision) {
        return submit(mediaFile, goal, revision, AnalysisMode.GENERAL);
    }

    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision, AnalysisMode mode) {
        return submit(mediaFile, goal, revision, mode, QuotaScope.INTERACTIVE);
    }

    /**
     * 后台自动任务入口（契约 §7.4）：默认视频笔记走这里。
     *
     * <p>与交互式入口共享同一套投递内核（活跃键、消息结构、Consumer、Checkpoint、死信），
     * 只有速率护栏不同——自动任务必须有自己的后台速率，否则 50 个单元的合集会因为
     * "每分钟 5 次" 的交互配额而大面积延迟。
     */
    public SubmissionResult submitBackground(MediaFile mediaFile, String goal, AgentFeedback revision,
                                            AnalysisMode mode) {
        return submit(mediaFile, goal, revision, mode, QuotaScope.BACKGROUND_NOTE);
    }

    private SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision,
                                    AnalysisMode mode, QuotaScope scope) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        Long mediaId = mediaFile.getId();
        String action = revision == null
                ? AnalysisTaskMsg.START_ANALYSIS
                : AnalysisTaskMsg.REVISE_ANALYSIS;
        String contentHash = revision == null ? contentHash(mediaId) : "media-" + mediaId;
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, resolvedMode);
        String activeKey = AnalysisTaskKeys.active(contentHash, goalDigest);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(mediaId), ACTIVE_TTL);
        if (!Boolean.TRUE.equals(accepted)) return SubmissionResult.DUPLICATE;

        try {
            if (!tryAcquireQuota(mediaFile.getUserId(), scope)) {
                redisTemplate.delete(activeKey);
                return SubmissionResult.RATE_LIMITED;
            }
            // 旧结果先留着。消费者真正接手后再切 Checkpoint，MQ 投递失败时用户还有结果可看。
            if (revision != null) aiService.stageRevision(revision, resolvedMode);
            rocketMQTemplate.convertAndSend(
                    analysisTopic,
                    new AnalysisTaskMsg(mediaId, action, contentHash, goal, resolvedMode.name()));
        } catch (RuntimeException e) {
            redisTemplate.delete(activeKey);
            if (revision != null) aiService.cancelStagedRevision(mediaId, goal, resolvedMode);
            log.error("analysis_dispatch_failed mediaId={} userId={}", mediaId, mediaFile.getUserId(), e);
            return SubmissionResult.FAILED;
        }

        try {
            taskEventService.publishAnalysis(mediaId, goal, resolvedMode,
                    TaskStatus.of(TaskStatus.State.QUEUED, "任务已进入异步分析队列"), TaskStage.QUEUED);
        } catch (RuntimeException eventError) {
            // MQ 已经接单，通知失败不能把任务伪装成投递失败。
            log.warn("analysis_queued_event_failed mediaId={} userId={}",
                    mediaId, mediaFile.getUserId(), eventError);
        }
        return SubmissionResult.ACCEPTED;
    }

    public boolean isActive(Long mediaId, String goal) {
        return isActive(mediaId, goal, AnalysisMode.GENERAL);
    }

    public boolean isActive(Long mediaId, String goal, AnalysisMode mode) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        return Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active(contentHash(mediaId), goalDigest)))
                || Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active("media-" + mediaId, goalDigest)));
    }

    /**
     * 释放被"僵尸"任务占用的活跃键。
     *
     * <p>活跃键的 TTL 是 6 小时。持键的进程如果被杀掉（崩溃、重启、机器故障），键会一直留着，
     * 期间任何重投都会被"已有活跃任务"挡回，媒体就永久停在 {@code ANALYZING}。
     * 恢复扫描在确认检查点长时间没有推进之后调用本方法清掉键，然后重新投递任务。
     *
     * <p>只删除活跃键，不触碰任何检查点：已完成的 Context/Chunk 会被新任务复用，不需要从 ASR 重来。
     *
     * @return 是否真的删掉了键
     */
    public boolean releaseStaleActiveKey(Long mediaId, String goal, AnalysisMode mode) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        Long removed = redisTemplate.delete(List.of(
                AnalysisTaskKeys.active(contentHash(mediaId), goalDigest),
                AnalysisTaskKeys.active("media-" + mediaId, goalDigest)));
        boolean released = removed != null && removed > 0;
        if (released) {
            log.warn("analysis_stale_active_key_released mediaId={} removed={}", mediaId, removed);
        }
        return released;
    }

    /**
     * 追问和证据检索同样会触发模型调用，统一复用分析配额，避免绕过成本护栏。
     */
    public void requireAiQuota(Long userId) {
        try {
            if (!tryAcquireQuota(userId)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("ai_rate_limiter_unavailable userId={}", userId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 服务限流器暂不可用，请稍后再试");
        }
    }

    private boolean tryAcquireQuota(Long userId) {
        return tryAcquireQuota(userId, QuotaScope.INTERACTIVE);
    }

    /**
     * 速率护栏：交互式入口与后台自动入口使用完全独立的 Redisson 限流器。
     *
     * <p>自动笔记若复用交互式配额，多单元合集会在第 6 个单元起被拒绝（默认 5 次/分钟），
     * 只能靠恢复扫描逐轮补投，收敛速度受限于限流窗口（D-049）。
     */
    private boolean tryAcquireQuota(Long userId, QuotaScope scope) {
        if (scope == QuotaScope.BACKGROUND_NOTE) {
            RRateLimiter noteUserLimiter = redissonClient.getRateLimiter(
                    AnalysisTaskKeys.noteUserLimit(userId));
            noteUserLimiter.trySetRate(RateType.OVERALL,
                    noteProperties.getBackgroundUserPerMinute(), 1, RateIntervalUnit.MINUTES);
            if (!noteUserLimiter.tryAcquire()) return false;

            RRateLimiter noteGlobalLimiter = redissonClient.getRateLimiter(
                    AnalysisTaskKeys.noteGlobalLimit());
            noteGlobalLimiter.trySetRate(RateType.OVERALL,
                    noteProperties.getBackgroundGlobalPerMinute(), 1, RateIntervalUnit.MINUTES);
            return noteGlobalLimiter.tryAcquire();
        }

        RRateLimiter userLimiter = redissonClient.getRateLimiter("limit:ai:user:" + userId);
        userLimiter.trySetRate(RateType.OVERALL, USER_REQUESTS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
        if (!userLimiter.tryAcquire()) return false;

        RRateLimiter globalLimiter = redissonClient.getRateLimiter("limit:ai:global");
        globalLimiter.trySetRate(
                RateType.OVERALL, GLOBAL_REQUESTS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
        return globalLimiter.tryAcquire();
    }

    /** 投递配额口径：交互式入口用 AI 配额，后台默认笔记用独立后台速率。 */
    private enum QuotaScope {
        INTERACTIVE,
        BACKGROUND_NOTE
    }

    private String contentHash(Long mediaId) {
        return AnalysisTaskKeys.normalizeContentHash(
                mediaId, mediaService.contentHash(mediaId));
    }

    public enum SubmissionResult {
        ACCEPTED,
        RATE_LIMITED,
        DUPLICATE,
        FAILED
    }
}
