package com.example.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

/** Independent feature flag and versioned resources for minute-level RAG. */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "ai.minute-rag")
public class MinuteRagProperties {
    /** 生产默认启用；关闭仅用于显式回滚到历史 Chunk 检索。 */
    private boolean enabled = true;
    private String indexVersion = "RAG_MINUTE_V1";
    private String bm25Version = "BM25_SEGMENT_V1";
    private String denseCollection = "video_segment_dense_bge_m3_v1";
    private boolean rerankerEnabled = true;
    @Min(1) private int bm25TopK = 20;
    @Min(1) private int denseTopK = 20;
    @Min(1) private int rrfTopK = 20;
    @Min(1) private int rerankerTopK = 20;
    @Min(1) private int finalTopN = 8;
    @Min(1) private int rrfK = 60;
    /** P1：对实体、数字、单位等高信号词做轻量词法加权，不改变 RRF 主流程。 */
    private boolean lexicalBoostEnabled = true;
    private double exactTermBoost = 0.35D;
    private double numericTermBoost = 0.55D;
    /** P0：把初召回候选的前后邻接分钟补入 Reranker，修复跨分钟边界证据漏召回。 */
    private boolean adjacentExpansionEnabled = true;
    @Min(0) private int adjacentSegments = 1;
    @Min(1) private int adjacentSeedTopK = 4;
    @Min(0) private int adjacentRerankerReserve = 8;
}
