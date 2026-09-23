package com.example.server.evaluation.runner;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.VideoEvidenceRetrievalService;
import org.springframework.stereotype.Component;

import java.util.List;

/** 用真实 V2 Chunk 与 {@link VideoEvidenceRetrievalService} 重放评测查询的 Top-5 排名。 */
@Component
public class ProductionRetrievalProbe implements RetrievalProbe {

    private final AgentCheckpointService checkpointService;
    private final VideoEvidenceRetrievalService retrievalService;

    public ProductionRetrievalProbe(AgentCheckpointService checkpointService,
                                    VideoEvidenceRetrievalService retrievalService) {
        this.checkpointService = checkpointService;
        this.retrievalService = retrievalService;
    }

    @Override
    public ProbeResult top5(ProbeRequest request) {
        List<VideoChunk> chunks = checkpointService.loadChunks(request.mediaId());
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("V2 chunks unavailable for mediaId=" + request.mediaId());
        }
        List<VideoEvidenceHit> hits = retrievalService.search(
                request.mediaId(), request.query(), chunks);
        return new ProbeResult(hits);
    }
}
