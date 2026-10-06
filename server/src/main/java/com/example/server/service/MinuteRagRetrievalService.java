package com.example.server.service;

import com.example.server.config.MinuteRagProperties;
import com.example.server.dto.SegmentRetrievalDocument;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.RetrievalScoreBreakdown;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.service.SiliconFlowRerankerClient.RerankResult;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** BM25 + BGE-M3 + rank-only RRF + existing reranker over minute documents. */
@Service
public class MinuteRagRetrievalService {
    private final MinuteRagIndexService indexService;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;
    private final SiliconFlowRerankerClient rerankerClient;
    private final EvidenceVerificationService evidenceVerificationService;
    private final MinuteRagProperties properties;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final Map<Long, CachedIndex> bm25Indexes = new java.util.concurrent.ConcurrentHashMap<>();

    public MinuteRagRetrievalService(MinuteRagIndexService indexService,
                                     EmbeddingUtils embeddingUtils,
                                     QdrantVectorStore vectorStore,
                                     SiliconFlowRerankerClient rerankerClient,
                                     EvidenceVerificationService evidenceVerificationService,
                                     MinuteRagProperties properties,
                                     AgentCheckpointService checkpointService,
                                     AgentTelemetry telemetry) {
        this.indexService = indexService;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
        this.rerankerClient = rerankerClient;
        this.evidenceVerificationService = evidenceVerificationService;
        this.properties = properties;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
    }

    public List<VideoEvidenceHit> search(Long mediaId, QueryPlan plan) {
        List<SegmentRetrievalDocument> documents = indexService.documents(mediaId);
        if (documents.isEmpty()) return List.of();
        String fingerprint = documents.stream().map(SegmentRetrievalDocument::segmentRef).collect(Collectors.joining("|"));
        CachedIndex cached = bm25Indexes.compute(mediaId, (key, old) -> old != null && old.fingerprint().equals(fingerprint)
                ? old : new CachedIndex(fingerprint, new Bm25SegmentIndex(documents)));
        String lexicalQuery = String.join(" ", plan.standaloneQuery(), plan.semanticQuery(),
                String.join(" ", plan.originalTerms()), String.join(" ", plan.correctedTerms()),
                String.join(" ", plan.keywords()));
        List<Bm25SegmentIndex.ScoredDocument> lexicalScored = cached.index().searchWithScores(
                lexicalQuery, properties.getBm25TopK(), lexicalSignals(plan),
                properties.isLexicalBoostEnabled() ? properties.getExactTermBoost() : 0D,
                properties.isLexicalBoostEnabled() ? properties.getNumericTermBoost() : 0D);
        List<SegmentRetrievalDocument> lexical = lexicalScored.stream().map(Bm25SegmentIndex.ScoredDocument::document).toList();
        List<DenseCandidate> denseScored = denseCandidates(mediaId, plan.semanticQuery(), documents);
        List<SegmentRetrievalDocument> dense = denseScored.stream().map(DenseCandidate::document).toList();
        List<Fused> fused = fuse(lexical, dense);
        telemetry.incrementCurrent("minuteBm25Candidates", lexical.size());
        telemetry.incrementCurrent("minuteDenseCandidates", dense.size());
        telemetry.incrementCurrent("minuteRrfCandidates", fused.size());
        if (fused.isEmpty()) return List.of();
        List<Fused> expanded = expandAdjacent(fused, documents);
        telemetry.incrementCurrent("minuteAdjacentCandidates", expanded.stream().filter(Fused::adjacent).count());

        Map<String, Bm25SegmentIndex.ScoredDocument> lexicalByRef = lexicalScored.stream().collect(Collectors.toMap(
                item -> item.document().segmentRef(), item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<String, DenseCandidate> denseByRef = denseScored.stream().collect(Collectors.toMap(
                item -> item.document().segmentRef(), item -> item, (left, right) -> left, LinkedHashMap::new));
        List<Ranked> ranked = rerank(rerankerQuery(plan), expanded);
        VideoContext context = checkpointService.loadContext(mediaId);
        return ranked.stream().map(item -> toHit(item, lexicalByRef, denseByRef, context))
                .filter(hit -> context == null || evidenceVerificationService.supported(context, hit))
                .distinct().limit(properties.getFinalTopN()).toList();
    }

    private List<String> lexicalSignals(QueryPlan plan) {
        return Stream.of(plan.originalTerms(), plan.correctedTerms(), plan.keywords(), plan.visualKeywords())
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 保留原始问题作为 Reranker 主查询，同时补充 Planner 已确认的语义词和高置信别名。
     * 例如“通宵熬夜”在字幕中常写成“一个晚上读到天亮”，只把别名作为辅助锚点，
     * 不改写用户问题，也不引入新的模型调用。
     */
    private String rerankerQuery(QueryPlan plan) {
        String base = plan.standaloneQuery() == null ? "" : plan.standaloneQuery().trim();
        StringBuilder query = new StringBuilder(base);
        appendQueryPart(query, plan.semanticQuery());
        lexicalSignals(plan).forEach(term -> appendQueryPart(query, term));
        String normalized = base.replaceAll("\\s+", "");
        if (normalized.contains("通宵") || normalized.contains("熬夜")
                || normalized.contains("一晚上") || normalized.contains("一个晚上")
                || normalized.contains("读到天亮")) {
            appendQueryPart(query, "晚上 天亮");
        }
        return query.toString().trim();
    }

    private void appendQueryPart(StringBuilder query, String value) {
        if (value != null && !value.isBlank()) {
            if (query.length() > 0) query.append(' ');
            query.append(value.trim());
        }
    }

    private List<DenseCandidate> denseCandidates(Long mediaId, String query, List<SegmentRetrievalDocument> documents) {
        if (query == null || query.isBlank()) return List.of();
        List<Double> queryVector;
        try { queryVector = embeddingUtils.embed(query); }
        catch (RuntimeException e) { telemetry.incrementCurrent("minuteDenseQueryFallbacks", 1); return List.of(); }
        Map<String, SegmentRetrievalDocument> byRef = documents.stream().collect(Collectors.toMap(
                SegmentRetrievalDocument::segmentRef, d -> d, (left, right) -> left));
        try {
            List<QdrantVectorStore.SegmentVectorHit> remote = vectorStore.searchSegmentDense(mediaId,
                    documents.get(0).analysisVersion(), properties.getIndexVersion(), queryVector,
                    properties.getDenseTopK(), properties.getDenseCollection());
            if (!remote.isEmpty()) return java.util.stream.IntStream.range(0, remote.size()).mapToObj(i -> {
                QdrantVectorStore.SegmentVectorHit hit = remote.get(i);
                return new DenseCandidate(byRef.get(hit.segmentRef()), hit.score(), i + 1);
            }).filter(candidate -> candidate.document() != null).toList();
        } catch (RuntimeException e) { telemetry.incrementCurrent("minuteDenseQueryFallbacks", 1); }
        return documents.stream().filter(document -> !document.denseText().isBlank())
                .map(document -> new DenseCandidate(document, cosine(queryVector, embed(document.denseText())), 0))
                .sorted(Comparator.comparingDouble(DenseCandidate::score).reversed()
                        .thenComparing(DenseCandidate::segmentRef)).limit(properties.getDenseTopK()).toList();
    }

    private List<Double> embed(String text) { try { return embeddingUtils.embed(text); } catch (RuntimeException e) { return List.of(); } }
    private double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) return 0;
        double dot = 0, ll = 0, rr = 0;
        for (int i = 0; i < left.size(); i++) { dot += left.get(i) * right.get(i); ll += left.get(i) * left.get(i); rr += right.get(i) * right.get(i); }
        return ll == 0 || rr == 0 ? 0 : dot / Math.sqrt(ll * rr);
    }

    private List<Fused> fuse(List<SegmentRetrievalDocument> lexical, List<SegmentRetrievalDocument> dense) {
        Map<String, Fused> merged = new LinkedHashMap<>();
        for (int i = 0; i < lexical.size(); i++) add(merged, lexical.get(i), i + 1, true);
        for (int i = 0; i < dense.size(); i++) add(merged, dense.get(i), i + 1, false);
        return merged.values().stream().sorted(Comparator.comparingDouble(Fused::score).reversed()
                .thenComparing(fused -> fused.document().segmentRef())).limit(properties.getRrfTopK()).toList();
    }
    private void add(Map<String, Fused> merged, SegmentRetrievalDocument document, int rank, boolean lexical) {
        Fused prior = merged.get(document.segmentRef());
        double score = 1D / (properties.getRrfK() + rank) + (prior == null ? 0 : prior.score());
        merged.put(document.segmentRef(), new Fused(document, score,
                prior != null && prior.adjacent(),
                prior == null ? Integer.MAX_VALUE : prior.adjacentSeedRank(),
                prior == null ? Integer.MAX_VALUE : prior.adjacentDistance(),
                prior == null ? rank : Math.min(prior.sourceRank(), rank)));
    }

    private List<Ranked> rerank(String query, List<Fused> fused) {
        List<Fused> candidates = selectRerankerCandidates(fused);
        if (!properties.isRerankerEnabled()) return candidates.stream().limit(properties.getFinalTopN())
                .map(candidate -> new Ranked(candidate, candidate.score(), null, false, false)).toList();
        try {
            List<RerankResult> results = rerankerClient.rerank(query,
                    candidates.stream().map(candidate -> candidate.document().denseText()).toList(), properties.getFinalTopN());
            telemetry.incrementCurrent("minuteRerankerCalls", 1);
            return results.stream().map(result -> new Ranked(candidates.get(result.index()), result.score(), result.score(), true, false)).toList();
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("minuteRerankerFallbacks", 1);
            return candidates.stream().limit(properties.getFinalTopN())
                    .map(candidate -> new Ranked(candidate, candidate.score(), null, false, true)).toList();
        }
    }

    /**
     * P0 边界补证：分钟候选不是独立语义单元，初召回命中某分钟后，前后相邻片段也可能承接
     * 同一句话。邻接片段使用衰减后的 RRF 分数参与排序，并预留少量 Reranker 名额，避免被
     * 原始 Top-K 候选全部挤掉；原始候选优先保留，避免扩大候选集造成 Reranker 成本失控。
     */
    private List<Fused> expandAdjacent(List<Fused> fused, List<SegmentRetrievalDocument> documents) {
        if (!properties.isAdjacentExpansionEnabled()
                || properties.getAdjacentSegments() <= 0
                || properties.getAdjacentSeedTopK() <= 0) {
            return fused;
        }
        List<SegmentRetrievalDocument> ordered = documents.stream()
                .sorted(Comparator.comparingLong(SegmentRetrievalDocument::startMs)
                        .thenComparingLong(SegmentRetrievalDocument::endMs)
                        .thenComparing(SegmentRetrievalDocument::segmentRef))
                .toList();
        Map<String, Integer> positions = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) positions.put(ordered.get(i).segmentRef(), i);
        Map<String, Fused> merged = new LinkedHashMap<>();
        fused.forEach(item -> merged.put(item.document().segmentRef(), item));
        List<Fused> seeds = fused.stream()
                // lexical-only candidates can have a low RRF score but still carry the exact
                // boundary term; use the best channel rank when choosing expansion seeds.
                .sorted(Comparator.comparingInt(Fused::sourceRank)
                        .thenComparing(Comparator.comparingDouble(Fused::score).reversed())
                        .thenComparing(item -> item.document().segmentRef()))
                .limit(properties.getAdjacentSeedTopK()).toList();
        for (int seedRank = 0; seedRank < seeds.size(); seedRank++) {
            Fused seed = seeds.get(seedRank);
            Integer position = positions.get(seed.document().segmentRef());
            if (position == null) continue;
            for (int distance = 1; distance <= properties.getAdjacentSegments(); distance++) {
                addAdjacent(merged, ordered, position - distance, seed, seedRank, distance);
                addAdjacent(merged, ordered, position + distance, seed, seedRank, distance);
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(Fused::score).reversed()
                        .thenComparing(item -> item.document().segmentRef()))
                .toList();
    }

    private void addAdjacent(Map<String, Fused> merged,
                             List<SegmentRetrievalDocument> ordered,
                             int position,
                             Fused seed,
                             int seedRank,
                             int distance) {
        if (position < 0 || position >= ordered.size()) return;
        SegmentRetrievalDocument neighbor = ordered.get(position);
        if (merged.containsKey(neighbor.segmentRef())) return;
        double score = seed.score() * Math.pow(0.95D, distance);
        merged.put(neighbor.segmentRef(), new Fused(neighbor, score, true, seedRank, distance,
                seed.sourceRank()));
    }

    private List<Fused> selectRerankerCandidates(List<Fused> fused) {
        int limit = properties.getRerankerTopK();
        if (limit <= 0 || fused.size() <= limit) return fused;
        int reserve = Math.min(properties.getAdjacentRerankerReserve(), limit);
        List<Fused> adjacent = fused.stream().filter(Fused::adjacent)
                .sorted(Comparator.comparingInt(Fused::adjacentSeedRank)
                        .thenComparingInt(Fused::adjacentDistance)
                        .thenComparing(Comparator.comparingDouble(Fused::score).reversed())
                        .thenComparing(item -> item.document().segmentRef()))
                .limit(reserve).toList();
        List<Fused> original = fused.stream().filter(item -> !item.adjacent())
                .limit(Math.max(0, limit - adjacent.size())).toList();
        return java.util.stream.Stream.concat(original.stream(), adjacent.stream())
                .sorted(Comparator.comparingDouble(Fused::score).reversed()
                        .thenComparing(item -> item.document().segmentRef()))
                .toList();
    }

    private VideoEvidenceHit toHit(Ranked ranked, Map<String, Bm25SegmentIndex.ScoredDocument> lexical,
                                   Map<String, DenseCandidate> dense, VideoContext context) {
        SegmentRetrievalDocument document = ranked.fused().document();
        VideoContext.VideoSegment source = context == null ? document.toSegment() : context.segments().stream()
                .filter(segment -> document.startMs() == segment.startMs() && document.endMs() == segment.endMs()).findFirst().orElse(document.toSegment());
        List<String> ocr = document.ocrTexts();
        boolean hasTranscript = !document.transcript().isBlank(), hasOcr = !ocr.isEmpty();
        String sourceName = hasTranscript && hasOcr ? document.transcriptSource() + "+OCR" : hasOcr ? "OCR" : hasTranscript ? document.transcriptSource() : "时间片段";
        String snippet = hasTranscript ? document.transcript() : String.join(" ", ocr);
        if (snippet.isBlank()) snippet = "该时间段暂无可展示文本";
        List<String> channels = new ArrayList<>();
        if (lexical.containsKey(document.segmentRef())) channels.add("BM25");
        if (dense.containsKey(document.segmentRef())) channels.add("DENSE");
        if (ranked.fused().adjacent()) channels.add("ADJACENT");
        RetrievalScoreBreakdown breakdown = new RetrievalScoreBreakdown(
                lexical.get(document.segmentRef()) == null ? null : lexical.get(document.segmentRef()).score(),
                lexical.get(document.segmentRef()) == null ? null : lexical.get(document.segmentRef()).rank(),
                dense.get(document.segmentRef()) == null ? null : dense.get(document.segmentRef()).score(),
                dense.get(document.segmentRef()) == null ? null : dense.get(document.segmentRef()).rank(),
                ranked.fused().score(), ranked.rerankerScore(), null, ranked.score(), ranked.rerankerApplied(), ranked.rerankerFallback(), List.copyOf(channels));
        return new VideoEvidenceHit(document.startMs(), document.endMs(), sourceName,
                snippet.replaceAll("\\s+", " ").trim(), source.transcript(), ocr, ranked.score(),
                document.segmentRef(), source.evidenceFrames(), document.transcriptSource(), breakdown);
    }

    private record CachedIndex(String fingerprint, Bm25SegmentIndex index) { }
    private record DenseCandidate(SegmentRetrievalDocument document, double score, int rank) {
        String segmentRef() { return document.segmentRef(); }
    }
    private record Fused(SegmentRetrievalDocument document,
                         double score,
                         boolean adjacent,
                         int adjacentSeedRank,
                         int adjacentDistance,
                         int sourceRank) { }
    private record Ranked(Fused fused, double score, Double rerankerScore, boolean rerankerApplied, boolean rerankerFallback) { }
}
