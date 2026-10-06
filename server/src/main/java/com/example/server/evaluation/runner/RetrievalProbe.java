package com.example.server.evaluation.runner;

import com.example.server.dto.VideoEvidenceHit;

import java.util.List;

/**
 * 评测用只读检索探针。生产适配器必须复用真实 Chunk 与检索服务；测试可替换为固定排名。
 */
public interface RetrievalProbe {

    ProbeResult top5(ProbeRequest request);

    /** Optional wider probe; legacy adapters can continue to provide Top-5 only. */
    default ProbeResult topK(ProbeRequest request, int limit) {
        return top5(request);
    }

    record ProbeRequest(Long mediaId, String query) {
        public ProbeRequest {
            if (mediaId == null) throw new IllegalArgumentException("mediaId is required");
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        }
    }

    record ProbeResult(List<VideoEvidenceHit> rankedTop5) {
        public ProbeResult {
            rankedTop5 = rankedTop5 == null ? List.of()
                    : List.copyOf(rankedTop5);
        }

        public static ProbeResult topN(List<VideoEvidenceHit> hits, int limit) {
            int safeLimit = Math.max(1, limit);
            return new ProbeResult(hits == null ? List.of()
                    : hits.stream().limit(safeLimit).toList());
        }
    }
}
