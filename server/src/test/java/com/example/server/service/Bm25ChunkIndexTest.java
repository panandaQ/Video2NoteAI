package com.example.server.service;

import com.example.server.dto.ChunkRetrievalDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Bm25ChunkIndexTest {
    @Test
    void preservesTechnicalIdentifiersAndMatchesChineseBigrams() {
        ChunkRetrievalDocument cas = document("cas", "CAS compareAndSet AQS");
        ChunkRetrievalDocument unrelated = document("other", "数据库事务隔离");
        Bm25ChunkIndex index = new Bm25ChunkIndex(List.of(cas, unrelated));

        assertEquals("cas", index.search("compareAndSet", 5).get(0).chunkRef());
        assertEquals("other", index.search("事务", 5).get(0).chunkRef());
    }

    private ChunkRetrievalDocument document(String ref, String text) {
        return new ChunkRetrievalDocument(ref, 1L, "V2", "CHUNK_HYBRID_V1", "BM25_CHUNK_V1",
                0, 1, text, List.of(), List.of(), text);
    }
}
