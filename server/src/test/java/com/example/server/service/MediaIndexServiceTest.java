package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 检索索引就绪判定（D-069）。
 *
 * <p>它是导入完成边界的一部分：分块快照存在才允许把媒体推进到 {@code READY}；快照缺失时
 * 能用已落盘的上下文补建就补建，补不出来就如实返回未就绪，由调用方保持处理中。
 */
class MediaIndexServiceTest {

    private static final Long MEDIA_ID = 21L;
    private static final Long SOURCE_MEDIA_ID = 22L;

    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final VideoChunkingService chunkingService = mock(VideoChunkingService.class);
    private final VideoEvidenceRetrievalService retrievalService = mock(VideoEvidenceRetrievalService.class);

    private final MediaIndexService service =
            new MediaIndexService(checkpointService, chunkingService, retrievalService);

    @Test
    void existingChunksAreReusedWithoutRebuildingOrRewritingIndex() {
        List<VideoChunk> chunks = List.of(chunk(0, 300));
        when(checkpointService.loadChunks(MEDIA_ID)).thenReturn(chunks);

        // 版本匹配才复用（D-079）：V2 上下文命中 V2 快照
        MediaIndexService.ChunkSnapshot snapshot = service.ensureChunks(MEDIA_ID,
                new VideoContext("memory://segments", "", List.of(segment(0)),
                        null, VideoContext.ANALYSIS_VERSION_V2, List.of()));

        assertTrue(snapshot.reused());
        assertEquals(chunks, snapshot.chunks());
        verifyNoInteractions(chunkingService, retrievalService);
    }

    @Test
    void missingChunksAreBuiltThenSavedAndIndexed() {
        List<VideoChunk> built = List.of(chunk(0, 300));
        when(checkpointService.loadChunks(MEDIA_ID)).thenReturn(null);
        when(chunkingService.build(any(), any(), any())).thenReturn(built);

        MediaIndexService.ChunkSnapshot snapshot = service.ensureChunks(MEDIA_ID, List.of(segment(0)));

        assertFalse(snapshot.reused());
        // 先落盘再写索引：向量库是加速器，检查点才是可重建的真源。
        var order = org.mockito.Mockito.inOrder(checkpointService, retrievalService);
        order.verify(checkpointService).saveChunks(MEDIA_ID, built);
        order.verify(retrievalService).index(MEDIA_ID, built);
    }

    /** 没有素材就不构建：空分块写进检查点会让"索引已就绪"永远为真。 */
    @Test
    void noSegmentsMeansNothingToBuild() {
        when(checkpointService.loadChunks(MEDIA_ID)).thenReturn(List.of());

        MediaIndexService.ChunkSnapshot snapshot = service.ensureChunks(MEDIA_ID, List.of());

        assertTrue(snapshot.chunks().isEmpty());
        verifyNoInteractions(chunkingService, retrievalService);
    }

    @Test
    void ensureIndexedIsTrueWhenChunksAlreadyExist() {
        when(checkpointService.loadChunks(MEDIA_ID)).thenReturn(List.of(chunk(0, 300)));

        assertTrue(service.ensureIndexed(MEDIA_ID));
        verify(checkpointService, never()).loadContext(anyLong());
    }

    /** 复用路径的兜底：分块还没挂过来，但上下文已经在盘上 → 就地补建并写索引。 */
    @Test
    void ensureIndexedBuildsFromStoredContext() {
        when(checkpointService.loadChunks(MEDIA_ID)).thenReturn(null);
        when(checkpointService.loadContext(MEDIA_ID))
                .thenReturn(new VideoContext("http://minio/source.mp4", "goal", List.of(segment(0))));
        when(chunkingService.build(any(), any(), any())).thenReturn(List.of(chunk(0, 300)));

        assertTrue(service.ensureIndexed(MEDIA_ID));
        verify(checkpointService).saveChunks(MEDIA_ID, List.of(chunk(0, 300)));
        verify(retrievalService).index(MEDIA_ID, List.of(chunk(0, 300)));
    }

    /** 上下文都没有（例如崩溃在预处理之前）：如实返回未就绪，让调用方保持处理中并等待重试。 */
    @Test
    void ensureIndexedIsFalseWithoutContext() {
        when(checkpointService.loadChunks(MEDIA_ID)).thenReturn(null);
        when(checkpointService.loadContext(MEDIA_ID)).thenReturn(null);

        assertFalse(service.ensureIndexed(MEDIA_ID));
        verifyNoInteractions(chunkingService, retrievalService);
    }

    /** 检查点读取异常不能被当成"已就绪"：宁可让任务多等一轮，也不能提前宣告完成。 */
    @Test
    void checkpointFailureIsNotTreatedAsReady() {
        when(checkpointService.loadChunks(MEDIA_ID)).thenThrow(new IllegalStateException("db down"));
        when(checkpointService.loadContext(MEDIA_ID)).thenThrow(new IllegalStateException("db down"));

        assertFalse(service.isIndexed(MEDIA_ID));
        assertFalse(service.ensureIndexed(MEDIA_ID));
    }

    /**
     * 构建或写索引失败同样只表示"还没就绪"：本方法跑在 AI 生命周期回调与恢复扫描里，
     * 抛异常会打断主链或整轮扫描，代价远大于晚一轮完成。
     */
    @Test
    void buildFailureIsReportedAsNotReadyInsteadOfThrowing() {
        when(checkpointService.loadChunks(MEDIA_ID)).thenReturn(null);
        when(checkpointService.loadContext(MEDIA_ID))
                .thenReturn(new VideoContext("http://minio/source.mp4", "goal", List.of(segment(0))));
        when(chunkingService.build(any(), any(), any()))
                .thenThrow(new IllegalStateException("embedding api down"));

        assertFalse(service.ensureIndexed(MEDIA_ID));
    }

    private VideoChunk chunk(long startSeconds, long endSeconds) {
        return new VideoChunk(startSeconds * 1000, endSeconds * 1000, "摘要", List.of("关键词"),
                List.of(segment(startSeconds)), List.of(0.1, 0.2),
                VideoContext.ANALYSIS_VERSION_V2, null, null, null, null);
    }

    private VideoContext.VideoSegment segment(long startSeconds) {
        return new VideoContext.VideoSegment(startSeconds * 1000, (startSeconds + 300) * 1000,
                "字幕文本", List.of(), List.of());
    }
}
