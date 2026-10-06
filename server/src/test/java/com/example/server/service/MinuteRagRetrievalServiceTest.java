package com.example.server.service;

import com.example.server.config.MinuteRagProperties;
import com.example.server.dto.SegmentRetrievalDocument;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.service.QdrantVectorStore.SegmentVectorHit;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinuteRagRetrievalServiceTest {

    @Test
    void expandsAdjacentMinuteAfterInitialRecallBeforeFinalSelection() {
        Long mediaId = 77L;
        MinuteRagProperties properties = new MinuteRagProperties();
        properties.setRerankerEnabled(false);
        properties.setFinalTopN(3);
        properties.setAdjacentSeedTopK(1);
        properties.setAdjacentSegments(1);
        properties.setAdjacentRerankerReserve(2);

        VideoContext context = new VideoContext("memory://77", "", List.of(
                segment(0, 60_000, "seed evidence"),
                segment(60_000, 120_000, "continuation evidence"),
                segment(120_000, 180_000, "unrelated tail")
        ));
        List<SegmentRetrievalDocument> documents = new SegmentDocumentBuilder(properties).build(mediaId, context);

        MinuteRagIndexService indexService = mock(MinuteRagIndexService.class);
        when(indexService.documents(mediaId)).thenReturn(documents);
        EmbeddingUtils embeddings = mock(EmbeddingUtils.class);
        when(embeddings.embed(anyString())).thenReturn(List.of(1D, 0D));
        QdrantVectorStore vectors = mock(QdrantVectorStore.class);
        when(vectors.searchSegmentDense(anyLong(), anyString(), anyString(), anyList(), anyInt(), anyString()))
                .thenReturn(List.of(new SegmentVectorHit("77:0:60000", 0, 60_000, 1D)));
        AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
        when(checkpointService.loadContext(mediaId)).thenReturn(context);
        EvidenceVerificationService verification = mock(EvidenceVerificationService.class);
        when(verification.supported(any(VideoContext.class), any(VideoEvidenceHit.class))).thenReturn(true);

        MinuteRagRetrievalService service = new MinuteRagRetrievalService(
                indexService, embeddings, vectors, mock(SiliconFlowRerankerClient.class),
                verification, properties, checkpointService, mock(AgentTelemetry.class));

        List<VideoEvidenceHit> hits = service.search(mediaId,
                new QueryPlan("seed", "seed", List.of("seed"), List.of()));

        assertEquals(2, hits.size());
        assertEquals(0, hits.get(0).startMs());
        assertEquals(60_000, hits.get(1).startMs());
        assertTrue(hits.get(1).scoreBreakdown().channels().contains("ADJACENT"));
        assertEquals(null, hits.get(1).scoreBreakdown().bm25Score());
        assertEquals(null, hits.get(1).scoreBreakdown().denseScore());
    }

    private static VideoContext.VideoSegment segment(long startMs, long endMs, String text) {
        return new VideoContext.VideoSegment(startMs, endMs, text, List.of(), List.of(),
                TranscriptSource.CC, null);
    }
}
