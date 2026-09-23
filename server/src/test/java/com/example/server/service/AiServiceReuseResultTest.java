package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.mode.ModeRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨用户复用结果的契约（D-068 / D-069）。
 *
 * <p>复用的定义是"整份初始知识快照",不是"只把笔记抄过来"：上下文、分块与向量都要挂到当前媒体上，
 * 否则复用路径导入的视频会出现"能读到笔记、却检索不到任何证据"。
 *
 * <p>证据帧按对象地址定位，来源媒体的帧地址必须改写为当前媒体的对象地址——沿用来源地址会指向
 * 另一个用户的对象，且那个对象被删除后证据立即失效。
 */
class AiServiceReuseResultTest {

    private static final Long MEDIA_ID = 21L;
    private static final Long SOURCE_MEDIA_ID = 22L;
    private static final String TARGET_SOURCE = "http://minio/media/video-import/7/21/source.mp4";

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final VideoContextService videoContextService = mock(VideoContextService.class);
    private final LongVideoContextService longVideoContextService = mock(LongVideoContextService.class);
    private final AgentLoopService agentLoopService = mock(AgentLoopService.class);
    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final AgentTelemetry telemetry = mock(AgentTelemetry.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final TaskEventService taskEventService = mock(TaskEventService.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ModeRegistry modeRegistry = mock(ModeRegistry.class);
    private final MediaIndexService mediaIndexService = mock(MediaIndexService.class);
    private final com.example.server.service.ingest.ContentArtifactEnrichmentService artifactService =
            mock(com.example.server.service.ingest.ContentArtifactEnrichmentService.class);

    private final AiService service = new AiService(mediaFileMapper, videoContextService,
            longVideoContextService, agentLoopService, checkpointService, telemetry, mediaService,
            taskEventService, redissonClient, redisTemplate, modeRegistry, mediaIndexService,
            artifactService);

    @Test
    void reuseCopiesNoteAndMaterializesChunksWithLocalizedEvidenceFrames() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media());
        when(checkpointService.loadContext(SOURCE_MEDIA_ID)).thenReturn(new VideoContext(
                "http://minio/media/video-import/8/22/source.mp4", "goal",
                List.of(segment(0, List.of("http://minio/frames/8/22/f1.jpg")))));
        when(checkpointService.loadChunks(SOURCE_MEDIA_ID)).thenReturn(
                List.of(chunk(0, List.of(segment(0, List.of("http://minio/frames/8/22/f1.jpg"))))));

        assertTrue(service.reuseResult(MEDIA_ID, SOURCE_MEDIA_ID, state(), AnalysisMode.GENERAL));

        ArgumentCaptor<List<VideoChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaIndexService).saveAndIndex(eq(MEDIA_ID), captor.capture());
        List<VideoChunk> reused = captor.getValue();
        assertEquals(1, reused.size());
        assertEquals(List.of(0.1, 0.2), reused.get(0).embedding(), "向量直接复用，不重新计算");
        assertEquals(List.of(TARGET_SOURCE + "#timestampMs=0"),
                reused.get(0).rawSegments().get(0).evidenceFrames());
        // 笔记本身仍然照旧复制到当前媒体。
        verify(checkpointService).saveResult(eq(MEDIA_ID), any(AgentState.class), eq(AnalysisMode.GENERAL));
    }

    /** 来源媒体没有分块快照时跳过，交由索引就绪判定在完成边界处兜底。 */
    @Test
    void reuseWithoutSourceChunksSkipsIndexing() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media());
        when(checkpointService.loadContext(SOURCE_MEDIA_ID)).thenReturn(new VideoContext(
                TARGET_SOURCE, "goal", List.of(segment(0, List.of()))));
        when(checkpointService.loadChunks(SOURCE_MEDIA_ID)).thenReturn(List.of());

        assertTrue(service.reuseResult(MEDIA_ID, SOURCE_MEDIA_ID, state(), AnalysisMode.GENERAL));

        verify(mediaIndexService, never()).saveAndIndex(any(), any());
    }

    /** 来源媒体的上下文已经不在了（被清理）：不能复用，调用方会走正常分析链路。 */
    @Test
    void reuseIsRejectedWhenSourceContextIsGone() {
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(media());
        when(checkpointService.loadContext(SOURCE_MEDIA_ID)).thenReturn(null);

        assertFalse(service.reuseResult(MEDIA_ID, SOURCE_MEDIA_ID, state(), AnalysisMode.GENERAL));

        verify(mediaIndexService, never()).saveAndIndex(any(), any());
        verify(checkpointService, never()).saveResult(any(), any(), any());
    }

    private MediaFile media() {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(7L);
        media.setFilePath(TARGET_SOURCE);
        return media;
    }

    private AgentState state() {
        return new AgentState("默认视频笔记", null,
                new AnalysisResult("标题", List.of("结论"), List.of(), List.of(), List.of()),
                null, 1);
    }

    private VideoChunk chunk(long startSeconds, List<VideoContext.VideoSegment> segments) {
        return new VideoChunk(startSeconds * 1000, (startSeconds + 300) * 1000, "摘要",
                List.of("关键词"), segments, List.of(0.1, 0.2));
    }

    private VideoContext.VideoSegment segment(long startSeconds, List<String> frames) {
        return new VideoContext.VideoSegment(startSeconds * 1000, (startSeconds + 300) * 1000,
                "字幕文本", List.of(), frames);
    }
}
