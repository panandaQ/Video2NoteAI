package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.repository.AgentCheckpointRepository;
import com.example.server.utils.AnalysisTaskKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 契约测试：内容级 Context/Chunk 与目标级完成结果的持久化位置。
 *
 * <p>默认视频笔记与用户自定义目标必须共享同一份 ASR/OCR 产物，因此 Context 和 Chunk 按 `mediaId`
 * 存储（`media:context`、`media:chunks`），与用户目标无关；只有计划和最终结果按
 * `(mediaId, goalDigest)` 存储。S1 的默认笔记复用现有 Checkpoint，不允许新增结果表。
 */
class AgentCheckpointMediaScopeTest {

    private static final Long MEDIA_ID = 7L;
    private static final String GOAL = "生成默认视频笔记";
    private static final String REDIS_KEY = "agent:checkpoint:7";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
    private final AgentCheckpointService service =
            new AgentCheckpointService(redisTemplate, new ObjectMapper(), repository);

    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> setOperations = mock(SetOperations.class);

    @Test
    void contextIsPersistedUnderMediaScopeAndStrippedOfGoal() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        VideoContext context = new VideoContext("http://minio/media/object.mp4", GOAL, List.of(
                new VideoContext.VideoSegment(0, 1_000, "开场", List.of("画面文字"), List.of("frame-1"))),
                60_000L, VideoContext.ANALYSIS_VERSION_V2,
                List.of(new com.example.server.dto.VideoChapter("vp-0-0", "开场", 0, 60_000, 1, "BILIBILI_VIEW_POINT")));

        service.saveContext(MEDIA_ID, context);

        ArgumentCaptor<VideoContext> saved = ArgumentCaptor.forClass(VideoContext.class);
        // V2 内容检查点落 :v2 键（D-079），旧 media:context 保留只读
        verify(repository).write(eq(MEDIA_ID), eq("media:context:v2"), eq("media:stage"),
                eq(REDIS_KEY), eq("context"), eq(TaskStage.CONTEXT_COMPLETED), saved.capture());
        assertEquals("", saved.getValue().userGoal(), "内容级 Context 必须与用户目标解耦，才能被默认笔记和自定义目标共享");
        assertEquals(context.segments(), saved.getValue().segments());
        assertEquals(60_000L, saved.getValue().durationMs(), "V2 元数据不得在持久化时丢失（交付约束 2）");
        assertEquals(context.chapters(), saved.getValue().chapters());
    }

    @Test
    void chunksArePersistedUnderMediaScope() {
        List<VideoChunk> chunks = List.of(new VideoChunk(
                0, 300_000, "片段摘要", List.of("关键词"), List.of(), List.of(0.1, 0.2)));

        service.saveChunks(MEDIA_ID, chunks);

        verify(repository).write(eq(MEDIA_ID), eq("media:chunks:v2"), eq("media:stage"),
                eq(REDIS_KEY), eq("chunks"), eq(TaskStage.CHUNKS_COMPLETED), eq(chunks));
    }

    @Test
    void contextIsReadBackFromV2MediaScope() {
        service.loadContext(MEDIA_ID);

        verify(repository).read(eq(MEDIA_ID), eq("media:context:v2"), eq(REDIS_KEY),
                eq("context"), eq(VideoContext.class));
    }

    @Test
    void legacyContextStaysReadableUnderV1Key() {
        service.loadLegacyContext(MEDIA_ID);

        verify(repository).read(eq(MEDIA_ID), eq("media:context"), eq(REDIS_KEY),
                eq("context"), eq(VideoContext.class));
    }

    @Test
    void completedResultIsPersistedUnderGoalScope() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        AgentState state = new AgentState(GOAL, null, null,
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);
        String digest = AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL);

        service.saveResult(MEDIA_ID, state, AnalysisMode.GENERAL);

        verify(repository).write(eq(MEDIA_ID),
                eq("goal:" + digest + ":result"),
                eq("goal:" + digest + ":stage"),
                eq(REDIS_KEY + ":goal:" + digest),
                eq("result"),
                eq(TaskStage.ANALYSIS_COMPLETED),
                eq(state));
    }

    @Test
    void flaggedResultIsPersistedWithWarningStage() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        AgentState state = new AgentState(GOAL, null, null,
                new AgentState.CriticResult(false, List.of("证据不足"), List.of(), List.of(), List.of()), 2);
        String digest = AnalysisTaskKeys.goalDigest(GOAL, AnalysisMode.GENERAL);

        service.saveResult(MEDIA_ID, state, AnalysisMode.GENERAL);

        verify(repository).write(eq(MEDIA_ID), anyString(), anyString(), anyString(),
                eq("result"), eq(TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS), eq(state));
    }
}
