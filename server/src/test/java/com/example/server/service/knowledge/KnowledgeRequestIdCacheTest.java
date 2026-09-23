package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.utils.KnowledgeQuestionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * requestId 幂等映射降级契约（runbook §7）：命中仍回 MySQL 校验（真正的幂等由唯一键保证）、
 * Redis 异常读写都不改变业务结果、键由集中工具生成。
 */
class KnowledgeRequestIdCacheTest {

    private static final Long USER_ID = 7L;
    private static final String REQUEST_ID = "b19d07b8-0a36-4fbd-a22c-a1bc0df2018c";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final KnowledgeRequestIdCache cache =
            new KnowledgeRequestIdCache(redisTemplate, new KnowledgeQuestionProperties());

    @Test
    void hitReturnsMappingValueWithCentralizedKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("91:314");

        Optional<String> mapping = cache.find(USER_ID, REQUEST_ID);

        assertTrue(mapping.isPresent());
        assertEquals("91:314", mapping.get());
        verify(valueOperations).get(KnowledgeQuestionKeys.request(USER_ID, REQUEST_ID));
    }

    @Test
    void readFailureDegradesToEmptyAndDoesNotThrow() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertFalse(cache.find(USER_ID, REQUEST_ID).isPresent());
    }

    @Test
    void writeFailureIsLoggedAndSwallowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        cache.remember(USER_ID, REQUEST_ID, 91L, 314L); // 不抛出：受理已提交，回写只是快路径
    }

    @Test
    void rememberUsesConfiguredTtlAndCentralizedKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache.remember(USER_ID, REQUEST_ID, 91L, 314L);

        verify(valueOperations).set(
                eq(KnowledgeQuestionKeys.request(USER_ID, REQUEST_ID)),
                eq("91:314"),
                eq(Duration.ofHours(24)));
    }
}
