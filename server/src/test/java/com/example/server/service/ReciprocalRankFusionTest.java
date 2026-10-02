package com.example.server.service;

import com.example.server.dto.ChunkRetrievalDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReciprocalRankFusionTest {
    @Test
    void fusesByRankAndPromotesDocumentsReturnedByBothRoutes() {
        ChunkRetrievalDocument shared = document("shared");
        ChunkRetrievalDocument lexicalOnly = document("lexical");
        ChunkRetrievalDocument denseOnly = document("dense");

        List<ReciprocalRankFusion.FusedChunk> result = ReciprocalRankFusion.fuse(
                List.of(shared, lexicalOnly), List.of(shared, denseOnly), 60, 3);

        assertEquals(List.of("shared", "dense", "lexical"),
                result.stream().map(ReciprocalRankFusion.FusedChunk::chunkRef).toList());
        assertEquals(List.of("BM25", "DENSE"), result.get(0).sources());
    }

    private ChunkRetrievalDocument document(String ref) {
        return new ChunkRetrievalDocument(ref, 1L, "V2", "CHUNK_HYBRID_V1", "BM25_CHUNK_V1",
                0, 1, ref, List.of(ref), List.of(), ref);
    }
}
