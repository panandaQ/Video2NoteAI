package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.utils.KnowledgeQuestionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code requestId → {conversationId}:{turnId}} 的 Redis 幂等映射（runbook §7）。
 *
 * <p>它只是幂等快路径：<b>命中后仍然回 MySQL 校验</b>（真正幂等由
 * {@code uk_knowledge_turn_request} 保证），未命中或 Redis 故障直接查 MySQL，功能完全正确、
 * 只少一次定位。写入失败只记日志，不回滚受理事务。
 */
@Component
public class KnowledgeRequestIdCache {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRequestIdCache.class);

    private final StringRedisTemplate redisTemplate;
    private final KnowledgeQuestionProperties properties;

    public KnowledgeRequestIdCache(StringRedisTemplate redisTemplate,
                                   KnowledgeQuestionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** @return 命中时返回 {@code "conversationId:turnId"}；未命中或 Redis 异常返回空 */
    public Optional<String> find(Long userId, String requestId) {
        try {
            String value = redisTemplate.opsForValue()
                    .get(KnowledgeQuestionKeys.request(userId, requestId));
            return Optional.ofNullable(value);
        } catch (RuntimeException e) {
            log.warn("knowledge_request_cache_read_failed userId={} requestId={}", userId, requestId);
            return Optional.empty();
        }
    }

    public void remember(Long userId, String requestId, Long conversationId, Long turnId) {
        try {
            redisTemplate.opsForValue().set(
                    KnowledgeQuestionKeys.request(userId, requestId),
                    KnowledgeQuestionKeys.requestValue(conversationId, turnId),
                    Duration.ofHours(properties.getQuestion().getRequestTtlHours()));
        } catch (RuntimeException e) {
            log.warn("knowledge_request_cache_write_failed userId={} requestId={}", userId, requestId);
        }
    }
}
