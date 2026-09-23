package com.example.server.evaluation.runner;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.VideoEvidenceRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionRetrievalProbeTest {

    @Test
    void loadsV2ChunksAndUsesRealSearchThenKeepsTopFive() {
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        VideoEvidenceRetrievalService retrieval = mock(VideoEvidenceRetrievalService.class);
        ProductionRetrievalProbe probe = new ProductionRetrievalProbe(checkpoints, retrieval);
        VideoChunk chunk = new VideoChunk(0, 1000, "summary", List.of(), List.of(), List.of());
        List<VideoEvidenceHit> hits = LongStream.range(0, 8)
                .mapToObj(index -> new VideoEvidenceHit(index * 10, index * 10 + 5,
                        "CC", "hit-" + index, "hit-" + index, List.of()))
                .toList();
        when(checkpoints.loadChunks(62L)).thenReturn(List.of(chunk));
        when(retrieval.search(62L, "独立问题", List.of(chunk))).thenReturn(hits);

        RetrievalProbe.ProbeResult result = probe.top5(
                new RetrievalProbe.ProbeRequest(62L, "独立问题"));

        assertEquals(5, result.rankedTop5().size());
        assertEquals("hit-0", result.rankedTop5().getFirst().snippet());
        verify(checkpoints).loadChunks(62L);
        verify(retrieval).search(62L, "独立问题", List.of(chunk));
    }
}
