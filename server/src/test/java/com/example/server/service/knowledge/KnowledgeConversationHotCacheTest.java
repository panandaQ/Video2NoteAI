package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.knowledge.KnowledgeConversationHotProjection;
import com.example.server.dto.knowledge.KnowledgeConversationResponse;
import com.example.server.dto.knowledge.KnowledgeConversationStatus;
import com.example.server.dto.knowledge.KnowledgeEvidenceResponse;
import com.example.server.dto.knowledge.KnowledgeScopeType;
import com.example.server.dto.knowledge.KnowledgeTurnResponse;
import com.example.server.dto.knowledge.KnowledgeTurnStatus;
import com.example.server.utils.KnowledgeQuestionKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeConversationHotCacheTest {

    private static final Long USER_ID = 7L;
    private static final Long CONVERSATION_ID = 91L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final KnowledgeQuestionProperties properties = new KnowledgeQuestionProperties();
    private final KnowledgeConversationHotCache cache =
            new KnowledgeConversationHotCache(redisTemplate, objectMapper, properties);

    @Test
    void validHitRefreshesSlidingTtl() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(objectMapper.writeValueAsString(projection(USER_ID, 3L, 4)));

        var result = cache.find(USER_ID, CONVERSATION_ID, 2L);

        assertTrue(result.isPresent());
        assertEquals(3L, result.get().version());
        verify(redisTemplate).expire(
                KnowledgeQuestionKeys.hotConversation(USER_ID, CONVERSATION_ID),
                Duration.ofMinutes(30));
    }

    @Test
    void staleKnownVersionIsMissAndEvicted() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(objectMapper.writeValueAsString(projection(USER_ID, 2L, 4)));

        assertFalse(cache.find(USER_ID, CONVERSATION_ID, 3L).isPresent());
        verify(redisTemplate).delete(KnowledgeQuestionKeys.hotConversation(USER_ID, CONVERSATION_ID));
        verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void crossUserPayloadAndBrokenJsonAreMisses() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString()))
                .thenReturn(objectMapper.writeValueAsString(projection(8L, 3L, 4)))
                .thenReturn("{broken");

        assertFalse(cache.find(USER_ID, CONVERSATION_ID, null).isPresent());
        assertFalse(cache.find(USER_ID, CONVERSATION_ID, null).isPresent());
    }

    @Test
    void redisFailureDegradesToMiss() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertFalse(cache.find(USER_ID, CONVERSATION_ID, null).isPresent());
    }

    @Test
    void oversizeProjectionIsNotWritten() {
        properties.getConversation().setHotMaxBytes(1024);
        var header = projection(USER_ID, 3L, 4).conversation();
        var huge = new KnowledgeConversationHotProjection(USER_ID, header, List.of(
                new com.example.server.dto.knowledge.KnowledgeTurnResponse(
                        1L, 1, "r", "q".repeat(2000), "", com.example.server.dto.knowledge.KnowledgeTurnStatus.COMPLETED,
                        "VIDEO_GROUNDED", "a".repeat(2000), null, List.of(), LocalDateTime.now(), LocalDateTime.now())));

        cache.remember(huge);

        verify(redisTemplate, never()).execute(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void oversizeProjectionDropsOldTurnsAndCompactsEvidenceBeforeWriting() throws Exception {
        properties.getConversation().setHotMaxBytes(1600);
        var header = projection(USER_ID, 3L, 2).conversation();
        var first = turn(1L, 1, "old".repeat(500));
        var second = turn(2L, 2, "new".repeat(500));

        cache.remember(new KnowledgeConversationHotProjection(USER_ID, header, List.of(first, second)));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), args.capture());
        KnowledgeConversationHotProjection written = objectMapper.readValue(
                (String) args.getValue()[0], KnowledgeConversationHotProjection.class);
        assertEquals(1, written.recentTurns().size());
        assertEquals(2, written.recentTurns().getFirst().turnNo());
        assertTrue(written.recentTurns().getFirst().evidence().getFirst().snippet().length() <= 512);
    }

    private KnowledgeConversationHotProjection projection(Long userId, long version, int lastTurnNo) {
        return new KnowledgeConversationHotProjection(userId, new KnowledgeConversationResponse(
                CONVERSATION_ID, KnowledgeScopeType.SINGLE_VIDEO, 27L, "标题",
                KnowledgeConversationStatus.ACTIVE, version, lastTurnNo,
                LocalDateTime.now(), LocalDateTime.now()), List.of());
    }

    private KnowledgeTurnResponse turn(Long id, int turnNo, String snippet) {
        return new KnowledgeTurnResponse(
                id, turnNo, "r-" + id, "问题", "改写", KnowledgeTurnStatus.COMPLETED,
                "VIDEO_GROUNDED", "回答", null,
                List.of(new KnowledgeEvidenceResponse(
                        1, 27L, "标题", 0, 1000, "ASR", snippet, 0.9)),
                LocalDateTime.now(), LocalDateTime.now());
    }
}
