package com.example.server.service.knowledge;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.knowledge.KnowledgeScopeType;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.exception.BusinessException;
import com.example.server.config.MinuteRagProperties;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.MinuteRagRetrievalService;
import com.example.server.service.VideoEvidenceRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单视频检索端口契约（runbook §9）：强制单 mediaId、LIBRARY 稳定拒绝、
 * 分块快照缺失按检索不可用处理而不是“无证据”。
 *
 * <p>D-111 起规划与检索分离：{@link SingleVideoEvidenceRetriever#plan} 只委托查询规划，
 * {@link SingleVideoEvidenceRetriever#retrieve} 消费调用方持有的规划结果，不再自己调模型。
 */
class SingleVideoEvidenceRetrieverTest {

    private static final QueryPlan PLAN =
            new QueryPlan("独立问法", "检索语句", List.of("关键词"), List.of());

    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final VideoEvidenceRetrievalService retrievalService = mock(VideoEvidenceRetrievalService.class);
    private final MinuteRagRetrievalService minuteRetrievalService = mock(MinuteRagRetrievalService.class);
    private final SingleVideoEvidenceRetriever retriever =
            new SingleVideoEvidenceRetriever(checkpointService, retrievalService);

    @Test
    void planDelegatesToRetrievalService() {
        when(retrievalService.planQuery("原问题", List.of())).thenReturn(PLAN);

        assertEquals(PLAN, retriever.plan("原问题", List.of()));
    }

    @Test
    void minuteRagIsEnabledByDefault() {
        assertTrue(new MinuteRagProperties().isEnabled());
    }

    @Test
    void libraryScopeIsRejectedAsUnsupported() {
        RetrievalScope scope = new RetrievalScope(KnowledgeScopeType.LIBRARY, List.of(1L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> retriever.retrieve(scope, PLAN));

        assertTrue(e.getMessage().contains("KNOWLEDGE_SCOPE_NOT_SUPPORTED"));
        verify(checkpointService, never()).loadChunks(anyLong());
    }

    @Test
    void scopeMustContainExactlyOneMediaId() {
        assertThrows(IllegalArgumentException.class,
                () -> retriever.retrieve(
                        new RetrievalScope(KnowledgeScopeType.SINGLE_VIDEO, List.of()), PLAN));
        assertThrows(IllegalArgumentException.class,
                () -> retriever.retrieve(
                        new RetrievalScope(KnowledgeScopeType.SINGLE_VIDEO, List.of(1L, 2L)), PLAN));
    }

    @Test
    void missingChunkSnapshotIsRetrievalUnavailable() {
        when(checkpointService.loadChunks(27L)).thenReturn(List.of());

        assertThrows(KnowledgeRetrievalUnavailableException.class,
                () -> retriever.retrieve(RetrievalScope.singleVideo(27L), PLAN));
        verify(retrievalService, never()).search(anyLong(), any(QueryPlan.class), anyList());
    }

    @Test
    void retrievesThroughExistingHybridSearch() {
        List<VideoChunk> chunks = List.of(new VideoChunk(0, 300_000, "摘要", List.of(), List.of(), List.of()));
        when(checkpointService.loadChunks(27L)).thenReturn(chunks);
        VideoEvidenceHit hit = new VideoEvidenceHit(10_000, 20_000, "ASR", "片段", "", List.of());
        when(retrievalService.search(27L, PLAN, chunks)).thenReturn(List.of(hit));

        RetrievalResult result = retriever.retrieve(RetrievalScope.singleVideo(27L), PLAN);

        assertEquals("HYBRID", result.retrievalMode());
        assertEquals(1, result.retrievedCount());
        assertEquals(List.of(hit), result.hits());
        verify(retrievalService).search(eq(27L), eq(PLAN), eq(chunks));
    }

    @Test
    void productionUsesMinuteRagAndDoesNotFallbackToChunkSearch() {
        MinuteRagProperties properties = new MinuteRagProperties();
        properties.setEnabled(true);
        VideoContext context = new VideoContext("memory://media", "goal", List.of(
                new VideoContext.VideoSegment(0, 60_000, "分钟字幕", List.of(), List.of())));
        VideoEvidenceHit hit = new VideoEvidenceHit(0, 60_000, "CC", "分钟片段", "分钟字幕", List.of());
        when(checkpointService.loadContext(27L)).thenReturn(context);
        when(minuteRetrievalService.search(27L, PLAN)).thenReturn(List.of(hit));

        SingleVideoEvidenceRetriever productionRetriever = new SingleVideoEvidenceRetriever(
                checkpointService, retrievalService, minuteRetrievalService, properties);

        RetrievalResult result = productionRetriever.retrieve(RetrievalScope.singleVideo(27L), PLAN);

        assertEquals("MINUTE_RAG", result.retrievalMode());
        assertEquals(List.of(hit), result.hits());
        verify(minuteRetrievalService).search(27L, PLAN);
        verify(retrievalService, never()).search(anyLong(), any(QueryPlan.class), anyList());
        verify(checkpointService, never()).loadChunks(anyLong());
    }

    @Test
    void minuteRagDoesNotFallbackWhenContextIsNotReady() {
        MinuteRagProperties properties = new MinuteRagProperties();
        properties.setEnabled(true);
        when(checkpointService.loadContext(27L)).thenReturn(null);

        SingleVideoEvidenceRetriever productionRetriever = new SingleVideoEvidenceRetriever(
                checkpointService, retrievalService, minuteRetrievalService, properties);

        assertThrows(KnowledgeRetrievalUnavailableException.class,
                () -> productionRetriever.retrieve(RetrievalScope.singleVideo(27L), PLAN));
        verifyNoChunkFallback();
    }

    private void verifyNoChunkFallback() {
        verify(retrievalService, never()).search(anyLong(), any(QueryPlan.class), anyList());
        verify(checkpointService, never()).loadChunks(anyLong());
    }
}
