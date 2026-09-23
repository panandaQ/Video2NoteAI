package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.utils.VideoImportKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 请求指纹到 {@code importId} 的热点映射。
 *
 * <p>只是加速：Redis 命中后仍要回源 MySQL 校验任务仍是活跃状态，任何 Redis 异常都返回未命中，
 * 由调用方继续走数据库唯一约束路径（契约 §10 “任何 Redis 异常都进入 MySQL 路径”）。
 */
@Component
public class ImportRequestCache {

    private static final Logger log = LoggerFactory.getLogger(ImportRequestCache.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public ImportRequestCache(StringRedisTemplate redisTemplate, VideoImportProperties properties) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofHours(properties.getRequestCacheTtlHours());
    }

    /** @return 缓存的 importId；未命中或 Redis 不可用时返回 {@code null} */
    public Long findImportId(Long userId, String requestHash) {
        try {
            String value = redisTemplate.opsForValue().get(VideoImportKeys.request(userId, requestHash));
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("video_import_request_cache_corrupted userId={}", userId);
            return null;
        } catch (RuntimeException e) {
            log.warn("video_import_request_cache_read_failed userId={}", userId, e);
            return null;
        }
    }

    public void remember(Long userId, String requestHash, Long importId) {
        try {
            redisTemplate.opsForValue()
                    .set(VideoImportKeys.request(userId, requestHash), String.valueOf(importId), ttl);
        } catch (RuntimeException e) {
            log.warn("video_import_request_cache_write_failed userId={} importId={}", userId, importId, e);
        }
    }

    public void forget(Long userId, String requestHash) {
        try {
            redisTemplate.delete(VideoImportKeys.request(userId, requestHash));
        } catch (RuntimeException e) {
            log.warn("video_import_request_cache_evict_failed userId={}", userId, e);
        }
    }
}
