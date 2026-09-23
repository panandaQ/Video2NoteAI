package com.example.server.service.knowledge;

import com.example.server.dto.VideoEvidenceHit;

import java.util.List;

/**
 * 一次范围检索的结果（runbook §9）。
 *
 * <p>{@code hits} 是交给回答模型的候选证据（单视频最多 8 条）；{@code retrievalMode} 与
 * {@code retrievedCount} 是评测诊断字段。候选为空与“检索基础设施不可用”是两种语义：
 * 前者是正常的 {@code MODEL_KNOWLEDGE}（模型知识兜底，不拒答），后者必须抛出
 * {@link KnowledgeRetrievalUnavailableException}（D-086）。
 */
public record RetrievalResult(
        List<VideoEvidenceHit> hits,
        String retrievalMode,
        int retrievedCount
) {
    public RetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        retrievalMode = retrievalMode == null ? "" : retrievalMode;
    }
}
