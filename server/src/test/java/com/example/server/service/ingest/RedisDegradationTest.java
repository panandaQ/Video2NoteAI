package com.example.server.service.ingest;

import com.example.server.common.ErrorCode;
import com.example.server.config.VideoImportProperties;
import com.example.server.exception.BusinessException;
import com.example.server.utils.VideoImportKeys;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 降级契约（契约 §10：任何 Redis 异常都进入 MySQL 路径）。
 *
 * <p>为什么必须固定住：请求映射与新建限额都只是加速与护栏，正确性由 {@code active_request_key} 唯一约束
 * 和数据库 CAS 保证。如果 Redis 故障时这两处抛异常，整条导入链路会因为"缓存不可用"而整体不可用——
 * 这正是 AC-04 要排除的情况。
 */
class RedisDegradationTest {

    private static final Long USER_ID = 7L;
    private static final String REQUEST_HASH = "hash";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final VideoImportProperties properties = new VideoImportProperties();

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final ImportRequestCache cache = new ImportRequestCache(redisTemplate, properties);

    // ---------------------------------------------------------------- 请求映射

    @Test
    void requestCacheReturnsHitAndNormalisesCacheKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VideoImportKeys.request(USER_ID, REQUEST_HASH))).thenReturn("42");

        assertEquals(42L, cache.findImportId(USER_ID, REQUEST_HASH));
    }

    @Test
    void requestCacheReadFailureFallsBackToDatabase() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertNull(cache.findImportId(USER_ID, REQUEST_HASH), "读失败必须表现为未命中");
    }

    /** 缓存值被写坏（非法数字）同样只能表现为未命中，不能把异常抛给提交链路。 */
    @Test
    void corruptedCacheValueIsTreatedAsMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("not-a-number");

        assertNull(cache.findImportId(USER_ID, REQUEST_HASH));
    }

    @Test
    void requestCacheWriteAndEvictFailuresAreSwallowed() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(redisTemplate).delete(anyString());

        assertDoesNotThrow(() -> cache.remember(USER_ID, REQUEST_HASH, 42L));
        assertDoesNotThrow(() -> cache.forget(USER_ID, REQUEST_HASH));
    }

    @Test
    void requestCacheRemembersWithConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache.remember(USER_ID, REQUEST_HASH, 42L);

        verify(valueOperations).set(eq(VideoImportKeys.request(USER_ID, REQUEST_HASH)), eq("42"),
                eq(Duration.ofHours(properties.getRequestCacheTtlHours())));
    }

    // ---------------------------------------------------------------- 新建任务限额

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RRateLimiter userLimiter = mock(RRateLimiter.class);
    private final RRateLimiter ipLimiter = mock(RRateLimiter.class);
    private final RRateLimiter globalLimiter = mock(RRateLimiter.class);

    private final ImportJobQuota quota = new ImportJobQuota(redissonClient, properties);

    private static final String CLIENT_IP = "203.0.113.7";

    // ---------------------------------------------------------------- 提交总频率

    @Test
    void submissionQuotaUsesSeparateUserAndIpBuckets() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, ipLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(ipLimiter.tryAcquire()).thenReturn(true);

        assertDoesNotThrow(() -> quota.requireSubmissionQuota(USER_ID, CLIENT_IP));

        verify(redissonClient).getRateLimiter(VideoImportKeys.userSubmissionLimit(USER_ID));
        verify(redissonClient).getRateLimiter(VideoImportKeys.ipSubmissionLimit(CLIENT_IP));
        verify(userLimiter).trySetRate(any(), eq((long) properties.getUserSubmissionsPerMinute()),
                eq(1L), eq(org.redisson.api.RateIntervalUnit.MINUTES));
        verify(ipLimiter).trySetRate(any(), eq((long) properties.getIpSubmissionsPerMinute()),
                eq(1L), eq(org.redisson.api.RateIntervalUnit.MINUTES));
    }

    @Test
    void submissionQuotaRejectsAtIpLayerAfterUserAllows() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, ipLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(ipLimiter.tryAcquire()).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> quota.requireSubmissionQuota(USER_ID, CLIENT_IP));

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode());
    }

    @Test
    void submissionQuotaFailsOpenWhenRedisIsUnavailable() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> quota.requireSubmissionQuota(USER_ID, CLIENT_IP));
    }

    @Test
    void submissionQuotaSkipsIpBucketWhenClientIpIsUnknown() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);

        assertDoesNotThrow(() -> quota.requireSubmissionQuota(USER_ID, null));

        verify(redissonClient, org.mockito.Mockito.never())
                .getRateLimiter(org.mockito.ArgumentMatchers.startsWith("limit:video:import:submit:ip:"));
    }

    @Test
    void quotaPassesWhenAllThreeLimitersAllow() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, ipLimiter, globalLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(ipLimiter.tryAcquire()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);

        assertDoesNotThrow(() -> quota.requireNewJobQuota(USER_ID, CLIENT_IP));

        verify(redissonClient).getRateLimiter(VideoImportKeys.userNewJobLimit(USER_ID));
        verify(redissonClient).getRateLimiter(VideoImportKeys.ipNewJobLimit(CLIENT_IP));
        verify(redissonClient).getRateLimiter(VideoImportKeys.globalNewJobLimit());
    }

    /**
     * IP 层存在的理由：用户级限额按账号计数，而注册没有防护，多开账号即可把额度乘起来。
     * 同一 IP 超限必须 429，且**不能**再去消耗全局额度。
     */
    @Test
    void ipLimitRejectsWithoutConsumingGlobalQuota() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, ipLimiter, globalLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(ipLimiter.tryAcquire()).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> quota.requireNewJobQuota(USER_ID, CLIENT_IP));

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode());
        org.mockito.Mockito.verify(globalLimiter, org.mockito.Mockito.never()).tryAcquire();
    }

    /** 用户级先判：用户自己超限时不该去消耗 IP 与全局额度（越具体越先判）。 */
    @Test
    void userLimitIsCheckedBeforeIpAndGlobal() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, ipLimiter, globalLimiter);
        when(userLimiter.tryAcquire()).thenReturn(false);

        assertThrows(BusinessException.class, () -> quota.requireNewJobQuota(USER_ID, CLIENT_IP));

        org.mockito.Mockito.verify(ipLimiter, org.mockito.Mockito.never()).tryAcquire();
        org.mockito.Mockito.verify(globalLimiter, org.mockito.Mockito.never()).tryAcquire();
    }

    /** 来源未知（内部调用/无请求上下文）时跳过 IP 层，不改变用户级与全局级语义。 */
    @Test
    void missingClientIpSkipsIpLayer() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, globalLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);

        assertDoesNotThrow(() -> quota.requireNewJobQuota(USER_ID, null));
        assertDoesNotThrow(() -> quota.requireNewJobQuota(USER_ID, "  "));

        org.mockito.Mockito.verify(redissonClient, org.mockito.Mockito.never()).getRateLimiter(org.mockito.ArgumentMatchers.startsWith("limit:video:import:ip:"));
    }

    @Test
    void quotaRejectsWith429WhenUserLimitExceeded() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, globalLimiter);
        when(userLimiter.tryAcquire()).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> quota.requireNewJobQuota(USER_ID));

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode());
    }

    /** 限流器不可用时降级放行：唯一约束仍然兜底，不能因为护栏故障让新任务完全建不出来。 */
    @Test
    void quotaFailsOpenWhenLimiterUnavailable() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> quota.requireNewJobQuota(USER_ID, CLIENT_IP));
    }

    /** 429 是业务判定，不能被降级分支吞掉（否则限额形同虚设）。 */
    @Test
    void rateLimitedBusinessExceptionIsNotSwallowed() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, ipLimiter, globalLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(ipLimiter.tryAcquire()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> quota.requireNewJobQuota(USER_ID, CLIENT_IP));

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode());
    }

    /** 限额键由集中工具生成，不允许散落字符串。 */
    @Test
    void quotaUsesCentralKeyFactory() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(userLimiter, ipLimiter, globalLimiter);
        when(userLimiter.tryAcquire()).thenReturn(true);
        when(ipLimiter.tryAcquire()).thenReturn(true);
        when(globalLimiter.tryAcquire()).thenReturn(true);

        quota.requireNewJobQuota(USER_ID, CLIENT_IP);

        verify(redissonClient).getRateLimiter(VideoImportKeys.userNewJobLimit(USER_ID));
        verify(redissonClient).getRateLimiter(VideoImportKeys.ipNewJobLimit(CLIENT_IP));
        verify(redissonClient).getRateLimiter(VideoImportKeys.globalNewJobLimit());
        verify(userLimiter).trySetRate(any(), eq((long) properties.getUserNewJobsPerMinute()),
                eq(1L), eq(org.redisson.api.RateIntervalUnit.MINUTES));
        verify(ipLimiter).trySetRate(any(), eq((long) properties.getIpNewJobsPerMinute()),
                eq(1L), eq(org.redisson.api.RateIntervalUnit.MINUTES));
        verify(globalLimiter).trySetRate(any(), eq((long) properties.getGlobalNewJobsPerMinute()),
                eq(1L), eq(org.redisson.api.RateIntervalUnit.MINUTES));
    }
}
