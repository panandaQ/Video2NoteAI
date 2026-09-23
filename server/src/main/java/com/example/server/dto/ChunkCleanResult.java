package com.example.server.dto;

import java.util.List;

/**
 * 批量 Chunk 语义清洗的产物：每块一份，{@code chunkId} 原样回传用于批内关联。
 *
 * <p>{@code normalizedTerms} 是「适合检索的规范关键词」，落入 {@link VideoChunk#keywords()};
 * {@code originalTerms} 是需要保留召回的原始表达（含可能的同音/形近/专有名词转写错误），作为检索
 * 别名保留，不因纠错丢失原召回；{@code corrections} 是高置信纠错记录（raw → corrected → confidence），
 * 仅用于审计/评测，不参与 embedding 与关键词打分。
 *
 * <p>这是 D-111 在查询侧建立的「原始词 + 纠正词 + 纠错记录」模式在 Chunk 索引侧的对应物。
 */
public record ChunkCleanResult(
        String chunkId,
        String summary,
        List<String> normalizedTerms,
        List<String> originalTerms,
        List<ChunkCorrection> corrections
) {
    public ChunkCleanResult {
        chunkId = chunkId == null ? "" : chunkId.trim();
        summary = summary == null ? "" : summary.trim();
        normalizedTerms = normalize(normalizedTerms);
        originalTerms = normalize(originalTerms);
        corrections = corrections == null ? List.of() : List.copyOf(corrections);
    }

    /**
     * 单条高置信纠正。{@code confidence} 用 double（区别于 {@code QueryPlan.Correction.reason}
     * 的字符串理由字段），只记录上下文能明确判断的纠正。
     */
    public record ChunkCorrection(String raw, String corrected, double confidence) {
        public ChunkCorrection {
            raw = raw == null ? "" : raw.trim();
            corrected = corrected == null ? "" : corrected.trim();
            if (confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("correction confidence must be in [0,1]");
            }
        }
    }

    /** 批量清洗的完整响应：与输入 chunk 一一对应。 */
    public record ChunkCleanBatch(List<ChunkCleanResult> chunks) {
        public ChunkCleanBatch {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    private static List<String> normalize(List<String> terms) {
        if (terms == null) return List.of();
        return terms.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }
}
