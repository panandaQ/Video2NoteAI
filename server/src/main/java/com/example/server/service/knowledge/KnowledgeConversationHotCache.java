package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.knowledge.KnowledgeConversationHotProjection;
import com.example.server.dto.knowledge.KnowledgeConversationStatus;
import com.example.server.dto.knowledge.KnowledgeEvidenceResponse;
import com.example.server.dto.knowledge.KnowledgeTurnResponse;
import com.example.server.utils.KnowledgeQuestionKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 最近活跃会话的 Redis 热投影（模块二 P2）。
 *
 * <p>读取失败、损坏、版本落后或写入失败都退化为 miss；业务事实、权限、CAS 与单轮终态
 * 始终由 MySQL 决定。写入通过 Lua 比较 {@code version + lastTurnNo}，防止晚到的旧回填
 * 覆盖新投影：version 在终态递增，lastTurnNo 覆盖“受理后 version 尚未递增”的窗口。
 */
@Component
public class KnowledgeConversationHotCache {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConversationHotCache.class);

    private static final DefaultRedisScript<Long> MONOTONIC_WRITE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then
              local ok, decoded = pcall(cjson.decode, current)
              if ok and decoded['conversation'] then
                local oldVersion = tonumber(decoded['conversation']['version']) or -1
                local oldLastTurnNo = tonumber(decoded['conversation']['lastTurnNo']) or -1
                local newVersion = tonumber(ARGV[2]) or -1
                local newLastTurnNo = tonumber(ARGV[3]) or -1
                if oldVersion > newVersion or
                   (oldVersion == newVersion and oldLastTurnNo > newLastTurnNo) then
                  return 0
                end
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeQuestionProperties properties;

    public KnowledgeConversationHotCache(StringRedisTemplate redisTemplate,
                                         ObjectMapper objectMapper,
                                         KnowledgeQuestionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Redis-first 读取；knownVersion 非空时，落后于客户端已知版本的投影视为 miss。 */
    public Optional<KnowledgeConversationHotProjection> find(Long userId,
                                                              Long conversationId,
                                                              Long knownVersion) {
        String key = KnowledgeQuestionKeys.hotConversation(userId, conversationId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            KnowledgeConversationHotProjection projection = objectMapper.readValue(
                    json, KnowledgeConversationHotProjection.class);
            if (!isValid(projection, userId, conversationId)
                    || (knownVersion != null && projection.version() < knownVersion)) {
                deleteQuietly(key);
                return Optional.empty();
            }
            redisTemplate.expire(key, ttl());
            return Optional.of(projection);
        } catch (Exception e) {
            log.warn("knowledge_hot_cache_read_failed userId={} conversationId={}",
                    userId, conversationId);
            return Optional.empty();
        }
    }

    /** 模型上下文只接受与本次 MySQL 命令版本完全一致的投影。 */
    public Optional<KnowledgeConversationHotProjection> findExact(Long userId,
                                                                   Long conversationId,
                                                                   long expectedVersion) {
        return find(userId, conversationId, expectedVersion)
                .filter(projection -> projection.version() == expectedVersion);
    }

    /** 最佳努力、版本单调写入；超出单 Key 上限时不缓存，不截断 MySQL 真源。 */
    public void remember(KnowledgeConversationHotProjection projection) {
        if (projection == null || projection.conversation() == null || projection.userId() == null) {
            return;
        }
        try {
            String json = serializeWithinLimit(projection);
            if (json == null) {
                return;
            }
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            redisTemplate.execute(
                    MONOTONIC_WRITE,
                    List.of(KnowledgeQuestionKeys.hotConversation(
                            projection.userId(), projection.conversation().conversationId())),
                    json,
                    String.valueOf(projection.version()),
                    String.valueOf(projection.lastTurnNo()),
                    String.valueOf(ttl().toMillis()));
        } catch (Exception e) {
            log.warn("knowledge_hot_cache_write_failed userId={} conversationId={}",
                    projection.userId(), projection.conversation().conversationId());
        }
    }

    /**
     * 先淘汰最旧轮次，再压缩证据展示片段；问题和答案正文不静默截断。若单轮本身仍超限，
     * 放弃本次投影并回源 MySQL，避免为了命中率损坏用户内容。
     */
    private String serializeWithinLimit(KnowledgeConversationHotProjection projection) throws Exception {
        int maxBytes = properties.getConversation().getHotMaxBytes();
        List<KnowledgeTurnResponse> turns = new ArrayList<>(projection.recentTurns());
        KnowledgeConversationHotProjection candidate = projection;
        String json = objectMapper.writeValueAsString(candidate);
        while (json.getBytes(StandardCharsets.UTF_8).length > maxBytes && turns.size() > 1) {
            turns.removeFirst();
            candidate = new KnowledgeConversationHotProjection(
                    projection.userId(), projection.conversation(), turns);
            json = objectMapper.writeValueAsString(candidate);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes && !turns.isEmpty()) {
            List<KnowledgeTurnResponse> compacted = turns.stream()
                    .map(this::compactEvidenceSnippets)
                    .toList();
            candidate = new KnowledgeConversationHotProjection(
                    projection.userId(), projection.conversation(), compacted);
            json = objectMapper.writeValueAsString(candidate);
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            log.info("knowledge_hot_cache_skipped_oversize userId={} conversationId={} bytes={}",
                    projection.userId(), projection.conversation().conversationId(), bytes);
            return null;
        }
        return json;
    }

    private KnowledgeTurnResponse compactEvidenceSnippets(KnowledgeTurnResponse turn) {
        List<KnowledgeEvidenceResponse> evidence = turn.evidence().stream()
                .map(item -> new KnowledgeEvidenceResponse(
                        item.rank(), item.mediaId(), item.title(), item.startMs(), item.endMs(),
                        item.source(), truncate(item.snippet(), 512), item.score()))
                .toList();
        return new KnowledgeTurnResponse(
                turn.turnId(), turn.turnNo(), turn.requestId(), turn.question(), turn.rewrittenQuery(),
                turn.status(), turn.answerMode(), turn.answer(), turn.errorCode(), evidence,
                turn.createdAt(), turn.completedAt());
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    public void evict(Long userId, Long conversationId) {
        deleteQuietly(KnowledgeQuestionKeys.hotConversation(userId, conversationId));
    }

    private boolean isValid(KnowledgeConversationHotProjection projection,
                            Long userId,
                            Long conversationId) {
        return projection != null
                && userId.equals(projection.userId())
                && projection.conversation() != null
                && conversationId.equals(projection.conversation().conversationId())
                && projection.conversation().status() == KnowledgeConversationStatus.ACTIVE;
    }

    private Duration ttl() {
        return Duration.ofMinutes(properties.getConversation().getHotTtlMinutes());
    }

    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("knowledge_hot_cache_delete_failed keyHash={}", key.hashCode());
        }
    }
}
