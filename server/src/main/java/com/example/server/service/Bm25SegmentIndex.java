package com.example.server.service;

import com.example.server.dto.SegmentRetrievalDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Dependency-free BM25 index keyed by stable segmentRef. */
public final class Bm25SegmentIndex {
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Pattern SIGNAL_TERM_PATTERN = Pattern.compile("[\\p{L}\\p{N}\\u4E00-\\u9FFF]");
    private final List<Entry> entries;
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final double averageLength;

    public Bm25SegmentIndex(List<SegmentRetrievalDocument> documents) {
        entries = documents.stream().map(document -> new Entry(document, tokenize(document.lexicalText()))).toList();
        for (Entry entry : entries) entry.tokens().forEach(token -> documentFrequency.merge(token, 1, Integer::sum));
        averageLength = entries.stream().mapToInt(entry -> entry.tokens().size()).average().orElse(1D);
    }

    public List<ScoredDocument> searchWithScores(String query, int limit) {
        return searchWithScores(query, limit, List.of(), 0D, 0D);
    }

    /**
     * Searches with query-side signal terms.  The normal BM25 score remains the
     * primary signal; the optional boost is deliberately small and only applies
     * when an entity, number, unit, or other non-trivial term is present in the
     * document.  This prevents generic words such as "书" or "故事" from
     * dominating a long subtitle segment.
     */
    public List<ScoredDocument> searchWithScores(String query, int limit,
                                                  List<String> signalTerms,
                                                  double exactTermBoost,
                                                  double numericTermBoost) {
        List<String> terms = expandQueryTerms(query);
        if (terms.isEmpty()) return List.of();
        int total = entries.size();
        List<ScoredDocument> scored = entries.stream()
                .map(entry -> new ScoredDocument(entry.document(),
                        score(entry, terms, total, signalTerms, exactTermBoost, numericTermBoost), 0))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed()
                        .thenComparing(item -> item.document().segmentRef()))
                .limit(Math.max(0, limit)).toList();
        return java.util.stream.IntStream.range(0, scored.size())
                .mapToObj(i -> new ScoredDocument(scored.get(i).document(), scored.get(i).score(), i + 1)).toList();
    }

    private double score(Entry entry, List<String> terms, int total,
                         List<String> signalTerms,
                         double exactTermBoost,
                         double numericTermBoost) {
        List<String> tokens = entry.tokens();
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
        return result + signalBoost(entry.normalizedText(), signalTerms, exactTermBoost, numericTermBoost);
    }

    private double signalBoost(String normalizedText,
                               List<String> signalTerms,
                               double exactTermBoost,
                               double numericTermBoost) {
        if (normalizedText.isBlank() || signalTerms == null || signalTerms.isEmpty()) return 0D;
        double result = 0D;
        for (String signal : signalTerms) {
            String normalizedSignal = normalizeText(signal);
            if (!isSignalTerm(normalizedSignal)) continue;
            // Prefer one exact phrase match.  Only fall back to at most two
            // high-confidence aliases when the phrase itself is absent.
            if (containsTerm(normalizedText, normalizedSignal)) {
                result += isNumericOrLatin(normalizedSignal) ? numericTermBoost : exactTermBoost;
                continue;
            }
            int aliasMatches = 0;
            for (String alias : expandQueryTerms(signal)) {
                String normalizedAlias = normalizeText(alias);
                if (normalizedAlias.equals(normalizedSignal)
                        || !isSignalTerm(normalizedAlias)
                        || !containsTerm(normalizedText, normalizedAlias)) continue;
                result += isNumericOrLatin(normalizedAlias) ? numericTermBoost : exactTermBoost;
                if (++aliasMatches >= 2) break;
            }
        }
        return result;
    }

    private static boolean containsTerm(String normalizedText, String normalizedTerm) {
        if (normalizedTerm.isBlank()) return false;
        String compactText = normalizedText.replace(" ", "");
        String compactTerm = normalizedTerm.replace(" ", "");
        return normalizedText.contains(normalizedTerm) || compactText.contains(compactTerm);
    }

    private static boolean isSignalTerm(String term) {
        if (term == null || term.isBlank() || term.length() < 2) return false;
        return SIGNAL_TERM_PATTERN.matcher(term).find()
                && !Set.of("问题", "什么", "哪个", "哪些", "如何", "为什么", "故事", "读书", "内容", "方面")
                .contains(term);
    }

    private static boolean isNumericOrLatin(String term) {
        return term.matches(".*[0-9].*") || term.matches(".*[a-z].*");
    }

    static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) return List.of();
        String normalized = normalizeText(value).replaceAll("[^\\p{L}\\p{N}_+#.-]+", " ");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) continue;
            tokens.add(token);
            if (token.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF) && token.length() > 1) {
                for (int i = 0; i + 1 < token.length(); i++) tokens.add(token.substring(i, i + 2));
            }
        }
        return new ArrayList<>(tokens);
    }

    /**
     * Adds only high-confidence aliases.  This is query expansion, not a
     * rewrite of the user's words, so the original query is still retained for
     * diagnostics and reranking.
     */
    static List<String> expandQueryTerms(String value) {
        List<String> base = tokenize(value);
        if (value == null || value.isBlank()) return base;
        String normalized = normalizeText(value).replaceAll("\\s+", "");
        LinkedHashSet<String> terms = new LinkedHashSet<>(base);
        if (normalized.contains("通宵") || normalized.contains("熬夜")
                || normalized.contains("一晚上") || normalized.contains("一个晚上")
                || normalized.contains("读到天亮")) {
            terms.addAll(tokenize("通宵 熬夜 晚上 天亮"));
        }
        return List.copyOf(terms);
    }

    static String normalizeText(String value) {
        if (value == null || value.isBlank()) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('０', '0').replace('１', '1').replace('２', '2')
                .replace('３', '3').replace('４', '4').replace('５', '5')
                .replace('６', '6').replace('７', '7').replace('８', '8')
                .replace('９', '9')
                .replaceAll("\\s+", " ").trim();
    }

    private record Entry(SegmentRetrievalDocument document, List<String> tokens, String normalizedText) {
        private Entry(SegmentRetrievalDocument document, List<String> tokens) {
            this(document, tokens, normalizeText(document.lexicalText()));
        }
    }
    public record ScoredDocument(SegmentRetrievalDocument document, double score, int rank) { }
}
