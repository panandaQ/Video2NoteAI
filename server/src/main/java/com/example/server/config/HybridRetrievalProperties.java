package com.example.server.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** SiliconFlow-backed multi-stage Chunk retrieval settings. */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "ai.hybrid-retrieval")
public class HybridRetrievalProperties {

    private boolean enabled = true;
    private boolean rerankerEnabled = true;
    private String indexVersion = "CHUNK_HYBRID_V1";
    private String bm25Version = "BM25_CHUNK_V1";
    private String denseCollection = "video_chunk_dense_bge_m3_v1";
    private String rerankerModel = "BAAI/bge-reranker-v2-m3";

    @Min(1)
    private int bm25TopK = 20;
    @Min(1)
    private int denseTopK = 20;
    @Min(1)
    private int rrfTopK = 20;
    @Min(1)
    private int rerankerTopK = 20;
    @Min(1)
    private int finalTopN = 8;
    @Min(1)
    private int rrfK = 60;
    @Min(1)
    private int rerankerTimeoutMs = 5_000;
    @Min(1)
    private int rerankerMaxRetries = 1;
    @Min(256)
    private int rerankerMaxInputChars = 6_000;
}
