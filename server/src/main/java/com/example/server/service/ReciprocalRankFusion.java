package com.example.server.service;

import com.example.server.dto.ChunkRetrievalDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rank-only fusion; raw BM25 and cosine scores never cross model boundaries. */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    public static List<FusedChunk> fuse(List<ChunkRetrievalDocument> lexical,
                                        List<ChunkRetrievalDocument> dense,
                                        int k,
                                        int limit) {
        Map<String, FusedChunk> fused = new HashMap<>();
        add(fused, lexical, k, "BM25");
        add(fused, dense, k, "DENSE");
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(FusedChunk::rrfScore).reversed()
                        .thenComparing(FusedChunk::chunkRef))
                .limit(Math.max(0, limit))
                .toList();
    }

    private static void add(Map<String, FusedChunk> fused,
                             List<ChunkRetrievalDocument> documents,
                             int k,
                             String source) {
        for (int i = 0; i < documents.size(); i++) {
            ChunkRetrievalDocument document = documents.get(i);
            FusedChunk current = fused.computeIfAbsent(document.chunkRef(),
                    key -> new FusedChunk(document, 0, new ArrayList<>()));
            current.rrfScore += 1D / (k + i + 1D);
            current.sources.add(source);
        }
    }

    public static final class FusedChunk {
        private final ChunkRetrievalDocument document;
        private double rrfScore;
        private final List<String> sources;

        private FusedChunk(ChunkRetrievalDocument document, double rrfScore, List<String> sources) {
            this.document = document;
            this.rrfScore = rrfScore;
            this.sources = sources;
        }

        public ChunkRetrievalDocument document() { return document; }
        public String chunkRef() { return document.chunkRef(); }
        public double rrfScore() { return rrfScore; }
        public List<String> sources() { return List.copyOf(sources); }
    }
}
