package com.example.server.service;

import com.example.server.dto.ChunkRetrievalDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrievalCoreTest {

    @Test
    void bm25KeepsTechnicalTermsAndOriginalAliasesTogether() {
        ChunkRetrievalDocument redisson = document("redisson", "Redisson 分布式锁", "Redisson", "redisson客户端");
        ChunkRetrievalDocument cas = document("cas", "CAS 与 AQS", "CAS", "compareAndSet");

        List<ChunkRetrievalDocument> hits = new Bm25ChunkIndex(List.of(redisson, cas))
                .search("Redisson", 2);

        assertEquals("redisson", hits.get(0).chunkRef());
    }

    @Test
    void rrfUsesRanksAndRewardsAgreementWithoutMixingRawScores() {
        ChunkRetrievalDocument both = document("both", "共同命中", "", "");
        ChunkRetrievalDocument lexicalOnly = document("lexical", "关键词", "", "");
        ChunkRetrievalDocument denseOnly = document("dense", "语义", "", "");

        List<ReciprocalRankFusion.FusedChunk> fused = ReciprocalRankFusion.fuse(
                List.of(both, lexicalOnly), List.of(both, denseOnly), 60, 3);

        assertEquals("both", fused.get(0).chunkRef());
        assertEquals(List.of("BM25", "DENSE"), fused.get(0).sources());
        assertTrue(fused.get(0).rrfScore() > fused.get(1).rrfScore());
    }

    private static ChunkRetrievalDocument document(String ref, String summary,
                                                    String keyword, String originalTerm) {
        return new ChunkRetrievalDocument(ref, 1L, "VIDEO_CONTEXT_V2", "CHUNK_HYBRID_V1",
                "BM25_CHUNK_V1", 0L, 300_000L, summary,
                keyword.isBlank() ? List.of() : List.of(keyword),
                originalTerm.isBlank() ? List.of() : List.of(originalTerm), "字幕内容");
    }
}
