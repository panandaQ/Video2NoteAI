package com.example.server.service;

import com.example.server.config.HybridRetrievalProperties;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.service.SiliconFlowRerankerClient.RerankResult;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridChunkRetrievalServiceTest {

    @Test
    void ccTranscriptWinsAndAsrIsNotPassedToReranker() {
        HybridRetrievalProperties properties = new HybridRetrievalProperties();
        properties.setRerankerEnabled(true);
        EmbeddingUtils embeddings = mock(EmbeddingUtils.class);
        when(embeddings.embed(anyString())).thenReturn(List.of(1D, 0D));
        QdrantVectorStore vectors = mock(QdrantVectorStore.class);
        when(vectors.searchDense(any(), any(), anyList(), anyInt(), anyString(), anyString())).thenReturn(List.of());
        SiliconFlowRerankerClient reranker = mock(SiliconFlowRerankerClient.class);
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(new RerankResult(0, 0.9D)));

        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                properties, embeddings, vectors, reranker, mock(AgentTelemetry.class));
        VideoChunk chunk = chunk(new VideoContext.VideoSegment(0, 60_000,
                "字幕原文", List.of(), List.of(), TranscriptSource.CC, null),
                new VideoContext.VideoSegment(0, 60_000,
                        "ASR 错误文本", List.of(), List.of(), TranscriptSource.ASR, null));

        List<VideoEvidenceHit> hits = service.search(1L,
                new QueryPlan("字幕", "字幕", List.of("字幕"), List.of()), List.of(chunk));

        assertEquals(1, hits.size());
        assertEquals("字幕原文", hits.get(0).transcript());
        assertEquals("CC", hits.get(0).transcriptSource());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> documents = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(reranker).rerank(anyString(), documents.capture(), anyInt());
        assertTrue(documents.getValue().get(0).contains("字幕原文"));
        assertTrue(!documents.getValue().get(0).contains("ASR 错误文本"));
    }

    @Test
    void asrIsMarkedFallbackOnlyWhenNoSubtitleExists() {
        HybridRetrievalProperties properties = new HybridRetrievalProperties();
        properties.setRerankerEnabled(false);
        EmbeddingUtils embeddings = mock(EmbeddingUtils.class);
        when(embeddings.embed(anyString())).thenReturn(List.of(1D, 0D));
        QdrantVectorStore vectors = mock(QdrantVectorStore.class);
        when(vectors.searchDense(any(), any(), anyList(), anyInt(), anyString(), anyString())).thenReturn(List.of());
        HybridChunkRetrievalService service = new HybridChunkRetrievalService(
                properties, embeddings, vectors, mock(SiliconFlowRerankerClient.class), mock(AgentTelemetry.class));

        List<VideoEvidenceHit> hits = service.search(1L,
                new QueryPlan("兜底", "兜底", List.of("兜底"), List.of()),
                List.of(chunk(new VideoContext.VideoSegment(0, 60_000,
                        "ASR 兜底文本", List.of(), List.of(), TranscriptSource.ASR, null))));

        assertEquals(1, hits.size());
        assertEquals("ASR 兜底文本", hits.get(0).transcript());
        assertEquals("ASR_FALLBACK", hits.get(0).transcriptSource());
    }

    private static VideoChunk chunk(VideoContext.VideoSegment... segments) {
        return new VideoChunk(0, 300_000, "字幕", List.of("字幕"), List.of(), List.of(),
                List.of(segments), List.of(), VideoContext.ANALYSIS_VERSION_V2,
                null, null, null, null);
    }
}
