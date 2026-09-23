package com.example.server.service;

import com.example.server.dto.ChunkCleanResult;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 章节感知分块契约（计划 §5.1 / §7.1）：章内最长五分钟切块、一个 Chunk 不跨两个 View 章节、
 * 未分章素材独立成组、章节字段与 analysisVersion 完整。
 *
 * <p>语义清洗扩展：批量清洗结果按 chunkId 回映射，规范检索词写 keywords、原始表达写 originalTerms；
 * 单批失败只降级该批，不中断整体构建。
 */
class VideoChunkingServiceTest {

    private final DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
    private final EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);

    private final VideoChunkingService service =
            new VideoChunkingService(deepSeekUtils, embeddingUtils, mock(AgentTelemetry.class));

    private static VideoContext.VideoSegment seg(long startMs, long endMs, String chapterId) {
        return new VideoContext.VideoSegment(startMs, endMs, "文本" + startMs,
                List.of(), List.of(), TranscriptSource.CC, chapterId);
    }

    /** 让 cleanChunks 按输入 batch 原样回传 chunkId，模拟正常的逐块清洗。 */
    private void mockCleanEcho() {
        when(deepSeekUtils.cleanChunks(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<VideoChunk> batch = invocation.getArgument(0);
            return batch.stream()
                    .map(chunk -> new ChunkCleanResult(
                            String.valueOf(chunk.startTime()), "摘要", List.of("关键词"),
                            List.of(), List.of()))
                    .toList();
        });
        when(embeddingUtils.embed(any())).thenReturn(List.of(0.1));
    }

    @Test
    void chapterLongerThanFiveMinutesIsSplitInsideTheChapter() {
        mockCleanEcho();

        List<VideoChapter> chapters = List.of(
                new VideoChapter("vp-0-0", "长章节", 0, 720_000, 1, "BILIBILI_VIEW_POINT"));
        List<VideoChunk> chunks = service.build(
                List.of(seg(0, 60_000, "vp-0-0"), seg(360_000, 420_000, "vp-0-0"),
                        seg(660_000, 720_000, "vp-0-0")),
                chapters);

        assertEquals(3, chunks.size(), "12 分钟章节 = 5+5+2 三块");
        for (VideoChunk chunk : chunks) {
            assertEquals("vp-0-0", chunk.chapterId());
            assertEquals("长章节", chunk.chapterTitle());
            assertEquals(0L, chunk.chapterStartMs());
            assertEquals(720_000L, chunk.chapterEndMs());
            assertEquals(VideoContext.ANALYSIS_VERSION_V2, chunk.analysisVersion());
            assertTrue(chunk.endTime() - chunk.startTime() <= 5 * 60 * 1000L);
        }
    }

    @Test
    void chunkNeverCrossesTwoChapters() {
        mockCleanEcho();

        List<VideoChapter> chapters = List.of(
                new VideoChapter("vp-0-0", "第一章", 0, 60_000, 1, "BILIBILI_VIEW_POINT"),
                new VideoChapter("vp-1-60000", "第二章", 60_000, 120_000, 1, "BILIBILI_VIEW_POINT"));
        List<VideoChunk> chunks = service.build(
                List.of(seg(30_000, 60_000, "vp-0-0"), seg(60_000, 90_000, "vp-1-60000")),
                chapters);

        assertEquals(2, chunks.size());
        assertEquals("vp-0-0", chunks.get(0).chapterId());
        assertEquals("vp-1-60000", chunks.get(1).chapterId());
        for (VideoChunk chunk : chunks) {
            for (VideoContext.VideoSegment raw : chunk.rawSegments()) {
                assertEquals(chunk.chapterId(), raw.chapterId(), "Chunk 内素材必须同章");
            }
        }
    }

    @Test
    void unassignedMaterialIsChunkedLastWithoutChapter() {
        mockCleanEcho();

        List<VideoChapter> chapters = List.of(
                new VideoChapter("vp-0-0", "正片", 0, 60_000, 1, "BILIBILI_VIEW_POINT"));
        List<VideoChunk> chunks = service.build(
                List.of(seg(0, 30_000, "vp-0-0"), seg(90_000, 120_000, null)),
                chapters);

        assertEquals(2, chunks.size());
        assertEquals("vp-0-0", chunks.get(0).chapterId(), "章节组按平台顺序在前");
        assertNull(chunks.get(1).chapterId(), "未分章素材最后，chapterId=null");
        assertNull(chunks.get(1).chapterTitle());
    }

    @Test
    void originalTermsAreCarriedAsRetrievalAliases() {
        when(deepSeekUtils.cleanChunks(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<VideoChunk> batch = invocation.getArgument(0);
            return batch.stream()
                    .map(chunk -> new ChunkCleanResult(
                            String.valueOf(chunk.startTime()), "规范摘要", List.of("树形结构"),
                            List.of("数形结构"),
                            List.of(new ChunkCleanResult.ChunkCorrection("数形结构", "树形结构", 0.95))))
                    .toList();
        });
        when(embeddingUtils.embed(any())).thenReturn(List.of(0.1));

        List<VideoChunk> chunks = service.build(List.of(seg(0, 60_000, null)), List.of());

        assertEquals(1, chunks.size());
        assertEquals("规范摘要", chunks.get(0).segmentSummary());
        assertEquals(List.of("树形结构"), chunks.get(0).keywords());
        assertEquals(List.of("数形结构"), chunks.get(0).originalTerms());
        assertEquals(1, chunks.get(0).corrections().size());
        assertEquals("数形结构", chunks.get(0).corrections().get(0).raw());
    }

    @Test
    void batchCleanFailureFallsBackToRawText() {
        when(deepSeekUtils.cleanChunks(any())).thenThrow(new RuntimeException("model down"));
        when(embeddingUtils.embed(any())).thenReturn(List.of(0.1));

        List<VideoChunk> chunks = service.build(List.of(seg(0, 60_000, null)), List.of());

        assertEquals(1, chunks.size());
        assertEquals("文本0", chunks.get(0).segmentSummary(), "降级为原文前 500 字");
        assertTrue(chunks.get(0).keywords().isEmpty());
        assertTrue(chunks.get(0).originalTerms().isEmpty());
        assertTrue(chunks.get(0).corrections().isEmpty());
    }

    @Test
    void emptySegmentsBuildNothing() {
        assertEquals(List.of(), service.build(List.of(), List.of()));
    }
}
