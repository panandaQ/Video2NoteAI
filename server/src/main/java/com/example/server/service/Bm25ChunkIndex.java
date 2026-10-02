package com.example.server.service;

import com.example.server.dto.ChunkRetrievalDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small dependency-free BM25 index for one authorized media snapshot. */
public final class Bm25ChunkIndex {
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<Entry> entries;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    public Bm25ChunkIndex(List<ChunkRetrievalDocument> documents) {
        this.entries = documents.stream().map(document -> new Entry(document, tokenize(document.lexicalText()))).toList();
        this.documentFrequency = new HashMap<>();
        for (Entry entry : entries) {
            entry.tokens.forEach(token -> documentFrequency.merge(token, 1, Integer::sum));
        }
        this.averageLength = entries.stream().mapToInt(entry -> entry.tokens.size()).average().orElse(1D);
    }

    public List<ChunkRetrievalDocument> search(String query, int limit) {
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) return List.of();
        int total = entries.size();
        return entries.stream()
                .map(entry -> new Scored(entry.document, score(entry.tokens, terms, total)))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(scored -> scored.document.chunkRef()))
                .limit(Math.max(0, limit))
                .map(Scored::document)
                .toList();
    }

    private double score(List<String> tokens, List<String> terms, int total) {
        Map<String, Integer> frequencies = new HashMap<>();
        tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
        double result = 0;
        for (String term : terms) {
            int df = documentFrequency.getOrDefault(term, 0);
            if (df == 0) continue;
            double idf = Math.log(1D + (total - df + 0.5D) / (df + 0.5D));
            int tf = frequencies.getOrDefault(term, 0);
            double denominator = tf + K1 * (1D - B + B * tokens.size() / averageLength);
            result += idf * tf * (K1 + 1D) / denominator;
        }
        return result;
    }

    static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) return List.of();
        String normalized = value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}_+#.-]+", " ");
        Set<String> tokens = new HashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) continue;
            tokens.add(token);
            if (containsCjk(token) && token.length() > 1) {
                for (int i = 0; i + 1 < token.length(); i++) {
                    tokens.add(token.substring(i, i + 2));
                }
            }
        }
        return new ArrayList<>(tokens);
    }

    private static boolean containsCjk(String token) {
        return token.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private record Entry(ChunkRetrievalDocument document, List<String> tokens) { }
    private record Scored(ChunkRetrievalDocument document, double score) { }
}
