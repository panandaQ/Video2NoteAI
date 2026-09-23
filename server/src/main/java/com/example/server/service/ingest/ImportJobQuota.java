package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.config.VideoImportProperties;
import com.example.server.exception.BusinessException;
import com.example.server.utils.VideoImportKeys;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 在线导入的提交频率与新建任务配额。
 *
 * <p>提交频率在读取请求缓存或 MySQL 前检查，防止同一热门 URL 的重复请求打满网关之后的 Redis 和数据库；
 * 新建任务配额只在真正创建父任务前检查，因此重复提交不消耗昂贵任务的容量。
 *
 * <p>三层顺序是**用户 → IP → 全局**：越具体的维度越先判，避免同一个来源的滥用先把全局额度吃掉、
 * 让正常用户跟着挨 429。IP 层存在的理由是用户级限额按账号计数，而**注册本身没有防护**，
 * 多开账号即可把用户级额度乘起来。
 *
 * <p>Redis 不可用时按契约 §10 的“任何 Redis 异常都进入 MySQL 路径”降级放行并记录，
 * 正确性仍由 {@code active_request_key} 唯一约束保证；不放行会让数据库兜底路径彻底不可用。
 */
@Component
public class ImportJobQuota {

    private static final Logger log = LoggerFactory.getLogger(ImportJobQuota.class);

    private final RedissonClient redissonClient;
    private final VideoImportProperties properties;

    public ImportJobQuota(RedissonClient redissonClient, VideoImportProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    /**
     * 每个有效 URL 提交都必须消耗一次用户/IP 请求额度。
     *
     * <p>它是削峰护栏，不参与幂等性或任务唯一性的正确性判断；Redis 故障时延续模块的降级放行策略。
     *
     * @param clientIp 来源地址，可为空（为空时跳过 IP 层）
     * @throws BusinessException 429：超过单用户或单 IP 的提交频率
     */
    public void requireSubmissionQuota(Long userId, String clientIp) {
        try {
            if (!tryAcquire(VideoImportKeys.userSubmissionLimit(userId),
                    properties.getUserSubmissionsPerMinute())) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "导入请求过于频繁，请稍后再试");
            }
            if (clientIp != null && !clientIp.isBlank()
                    && !tryAcquire(VideoImportKeys.ipSubmissionLimit(clientIp),
                    properties.getIpSubmissionsPerMinute())) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "导入请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("video_import_submission_quota_degraded userId={} ip={}", userId, clientIp, e);
        }
    }

    /**
     * @param clientIp 来源地址，可为空（为空时跳过 IP 层，不改变用户级与全局级语义）
     * @throws BusinessException 429：超过单用户、单 IP 或全局新建任务限额
     */
    public void requireNewJobQuota(Long userId, String clientIp) {
        try {
            if (!tryAcquire(VideoImportKeys.userNewJobLimit(userId),
                    properties.getUserNewJobsPerMinute())) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "导入请求过于频繁，请稍后再试");
            }
            if (clientIp != null && !clientIp.isBlank()
                    && !tryAcquire(VideoImportKeys.ipNewJobLimit(clientIp),
                    properties.getIpNewJobsPerMinute())) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "导入请求过于频繁，请稍后再试");
            }
            if (!tryAcquire(VideoImportKeys.globalNewJobLimit(),
                    properties.getGlobalNewJobsPerMinute())) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "导入请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("video_import_quota_degraded userId={} ip={}", userId, clientIp, e);
        }
    }

    /** 兼容只关心用户级配额的老调用方（例如不经过 HTTP 的内部调用）。 */
    public void requireNewJobQuota(Long userId) {
        requireNewJobQuota(userId, null);
    }

    private boolean tryAcquire(String key, int permitsPerMinute) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.trySetRate(RateType.OVERALL, permitsPerMinute, 1, RateIntervalUnit.MINUTES);
        return limiter.tryAcquire();
    }
}
