package com.example.server.evaluation.runner;

import com.example.server.dto.VideoEvidenceHit;

import java.util.List;

/**
 * 评测用只读检索探针。生产适配器必须复用真实 Chunk 与检索服务；测试可替换为固定排名。
 */
public interface RetrievalProbe {

    ProbeResult top5(ProbeRequest request);

    record ProbeRequest(Long mediaId, String query) {
        public ProbeRequest {
            if (mediaId == null) throw new IllegalArgumentException("mediaId is required");
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        }
    }

    record ProbeResult(List<VideoEvidenceHit> rankedTop5) {
        public ProbeResult {
            rankedTop5 = rankedTop5 == null ? List.of()
                    : List.copyOf(rankedTop5.stream().limit(5).toList());
        }
    }
}
