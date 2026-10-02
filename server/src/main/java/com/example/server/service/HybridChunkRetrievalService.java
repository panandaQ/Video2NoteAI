package com.example.server.service;

import com.example.server.config.HybridRetrievalProperties;
import com.example.server.dto.ChunkRetrievalDocument;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.service.SiliconFlowRerankerClient.RerankResult;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** BM25 + dense + RRF + rerank orchestration over the existing five-minute Chunk boundary. */
@Service
public class HybridChunkRetrievalService {
    private final HybridRetrievalProperties properties;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;
    private final SiliconFlowRerankerClient rerankerClient;
    private final AgentTelemetry telemetry;
    private final Map<Long, CachedIndex> bm25Indexes = new ConcurrentHashMap<>();

    public HybridChunkRetrievalService(HybridRetrievalProperties properties,
                                       EmbeddingUtils embeddingUtils,
                                       QdrantVectorStore vectorStore,
                                       SiliconFlowRerankerClient rerankerClient,
                                       AgentTelemetry telemetry) {
        this.properties = properties;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
        this.rerankerClient = rerankerClient;
        this.telemetry = telemetry;
    }

    public void index(Long mediaId, List<VideoChunk> chunks) {
        if (!properties.isEnabled() || mediaId == null || chunks == null || chunks.isEmpty()) return;
        List<ChunkRetrievalDocument> documents = documents(mediaId, chunks);
        bm25Indexes.put(mediaId, new CachedIndex(cacheVersion(chunks), new Bm25ChunkIndex(documents)));
        try {
            List<List<Double>> vectors = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                List<Double> existing = chunks.get(i).embedding();
                vectors.add(existing == null || existing.isEmpty()
                        ? embedTranscript(documents.get(i).transcript())
                        : existing);
            }
            vectorStore.upsertDense(mediaId, chunks, vectors, properties.getDenseCollection(),
                    properties.getIndexVersion());
            telemetry.incrementCurrent("hybridDenseWrites", vectors.stream().filter(v -> !v.isEmpty()).count());
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("hybridDenseWriteFallbacks", 1);
        }
    }

    public List<VideoEvidenceHit> search(Long mediaId, QueryPlan plan, List<VideoChunk> chunks) {
        if (!properties.isEnabled() || chunks == null || chunks.isEmpty()) return List.of();
        List<ChunkRetrievalDocument> documents = documents(mediaId, chunks);
        CachedIndex cached = bm25Indexes.compute(mediaId, (key, value) ->
                value != null && value.version().equals(cacheVersion(chunks))
                        ? value : new CachedIndex(cacheVersion(chunks), new Bm25ChunkIndex(documents)));

        String lexicalQuery = String.join(" ", plan.standaloneQuery(),
                String.join(" ", plan.originalTerms()), String.join(" ", plan.correctedTerms()),
                String.join(" ", plan.keywords()));
        List<ChunkRetrievalDocument> lexical = cached.index().search(lexicalQuery, properties.getBm25TopK());
        List<ChunkRetrievalDocument> dense = denseCandidates(mediaId, plan.semanticQuery(), documents, chunks);
        List<ReciprocalRankFusion.FusedChunk> fused = ReciprocalRankFusion.fuse(
                lexical, dense, properties.getRrfK(), properties.getRrfTopK());
        telemetry.incrementCurrent("hybridBm25Candidates", lexical.size());
        telemetry.incrementCurrent("hybridDenseCandidates", dense.size());
        telemetry.incrementCurrent("hybridRrfCandidates", fused.size());
        if (fused.isEmpty()) return List.of();

        List<RankedChunk> ranked = rerank(plan.standaloneQuery(), fused);
        Map<String, VideoChunk> chunksByRef = new LinkedHashMap<>();
        for (VideoChunk chunk : chunks) chunksByRef.put(chunkRef(mediaId, chunk), chunk);
        return expandEvidence(ranked, chunksByRef);
    }

    private List<ChunkRetrievalDocument> denseCandidates(Long mediaId,
                                                         String query,
                                                         List<ChunkRetrievalDocument> documents,
                                                         List<VideoChunk> chunks) {
        if (query == null || query.isBlank()) return List.of();
        List<Double> queryEmbedding;
        try {
            queryEmbedding = embeddingUtils.embed(query);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("hybridDenseQueryFallbacks", 1);
            return List.of();
        }
        try {
            Map<String, ChunkRetrievalDocument> byRange = documents.stream()
                    .collect(Collectors.toMap(d -> d.startMs() + ":" + d.endMs(), d -> d));
            List<ChunkRetrievalDocument> remote = vectorStore.searchDense(
                    mediaId, analysisVersion(chunks), queryEmbedding, properties.getDenseTopK(),
                    properties.getDenseCollection(), properties.getIndexVersion()).stream()
                    .map(hit -> byRange.get(hit.startMs() + ":" + hit.endMs()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            return remote.isEmpty() ? localDenseCandidates(queryEmbedding, documents) : remote;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("hybridDenseQueryFallbacks", 1);
            return localDenseCandidates(queryEmbedding, documents);
        }
    }

    private List<ChunkRetrievalDocument> localDenseCandidates(List<Double> queryVector,
                                                               List<ChunkRetrievalDocument> documents) {
        return documents.stream()
                .filter(document -> !document.transcript().isBlank())
                .sorted(Comparator.comparingDouble((ChunkRetrievalDocument document) ->
                        cosine(queryVector, embedTranscript(document.transcript()))).reversed())
                .limit(properties.getDenseTopK())
                .toList();
    }

    private List<Double> embedTranscript(String transcript) {
        if (transcript == null || transcript.isBlank()) return List.of();
        try {
            return embeddingUtils.embed(transcript);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("hybridDenseDocumentFallbacks", 1);
            return List.of();
        }
    }

    private List<RankedChunk> rerank(String query, List<ReciprocalRankFusion.FusedChunk> fused) {
        List<ReciprocalRankFusion.FusedChunk> candidates = fused.stream()
                .limit(properties.getRerankerTopK()).toList();
        if (!properties.isRerankerEnabled()) {
            return candidates.stream()
                    .limit(properties.getFinalTopN())
                    .map(item -> new RankedChunk(item, item.rrfScore()))
                    .toList();
        }
        List<String> rerankDocuments = candidates.stream()
                .map(item -> rerankText(item.document())).toList();
        try {
            List<RerankResult> result = rerankerClient.rerank(query, rerankDocuments, properties.getFinalTopN());
            telemetry.incrementCurrent("hybridRerankerCalls", 1);
            return result.stream().map(item -> new RankedChunk(candidates.get(item.index()), item.score())).toList();
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("hybridRerankerFallbacks", 1);
            return candidates.stream().map(item -> new RankedChunk(item, item.rrfScore())).toList();
        }
    }

    private List<VideoEvidenceHit> expandEvidence(List<RankedChunk> ranked,
                                                   Map<String, VideoChunk> chunksByRef) {
        Map<String, VideoEvidenceHit> deduped = new LinkedHashMap<>();
        for (RankedChunk rankedChunk : ranked) {
            VideoChunk chunk = chunksByRef.get(rankedChunk.chunk().chunkRef());
            if (chunk == null) continue;
            for (VideoContext.VideoSegment segment : chunk.rawSegments()) {
                String key = segment.startMs() + ":" + segment.endMs();
                deduped.putIfAbsent(key, toHit(segment, rankedChunk));
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private VideoEvidenceHit toHit(VideoContext.VideoSegment segment, RankedChunk ranked) {
        List<String> ocr = segment.ocrTexts().stream().filter(text -> text != null && !text.isBlank()).distinct().toList();
        boolean transcript = !segment.transcript().isBlank();
        boolean hasOcr = !ocr.isEmpty();
        String source = transcript && hasOcr ? segment.source().name() + "+OCR"
                : hasOcr ? "OCR" : transcript ? segment.source().name() : "时间片段";
        String visual = String.join(" ", ocr);
        String snippet = transcript ? segment.transcript() : visual;
        if (snippet.isBlank()) snippet = "该时间段暂无可展示文本";
        String transcriptSource = transcript
                ? segment.source() == TranscriptSource.CC ? "CC" : "ASR_FALLBACK"
                : "NONE";
        return new VideoEvidenceHit(segment.startMs(), segment.endMs(), source,
                snippet.replaceAll("\\s+", " ").trim(), segment.transcript(), ocr,
                ranked.score(), ranked.chunk().chunkRef(), segment.evidenceFrames(), transcriptSource);
    }

    private String rerankText(ChunkRetrievalDocument document) {
        String text = String.join("\n", document.summary(),
                "关键词: " + String.join(" ", document.keywords()),
                "原始术语: " + String.join(" ", document.originalTerms()),
                "transcriptSource: " + document.transcriptSource(),
                "字幕: " + document.transcript());
        return text.length() <= properties.getRerankerMaxInputChars()
                ? text : text.substring(0, properties.getRerankerMaxInputChars());
    }

    private List<ChunkRetrievalDocument> documents(Long mediaId, List<VideoChunk> chunks) {
        return chunks.stream().map(chunk -> {
            TranscriptSelection selection = preferredTranscript(chunk);
            if ("ASR_FALLBACK".equals(selection.source())) {
                telemetry.incrementCurrent("hybridTranscriptAsrFallbacks", 1);
            }
            return new ChunkRetrievalDocument(
                    chunkRef(mediaId, chunk), mediaId, analysisVersion(chunk), properties.getIndexVersion(),
                    properties.getBm25Version(), chunk.startTime(), chunk.endTime(), chunk.segmentSummary(),
                    chunk.keywords(), chunk.originalTerms(), selection.text(), selection.source());
        }).toList();
    }

    private TranscriptSelection preferredTranscript(VideoChunk chunk) {
        List<String> cc = chunk.rawSegments().stream()
                .filter(segment -> segment.source() == TranscriptSource.CC)
                .map(VideoContext.VideoSegment::transcript)
                .filter(text -> text != null && !text.isBlank())
                .toList();
        if (!cc.isEmpty()) return new TranscriptSelection(String.join("\n", cc), "CC");
        List<String> asr = chunk.rawSegments().stream()
                .filter(segment -> segment.source() == TranscriptSource.ASR)
                .map(VideoContext.VideoSegment::transcript)
                .filter(text -> text != null && !text.isBlank())
                .toList();
        return asr.isEmpty()
                ? new TranscriptSelection("", "NONE")
                : new TranscriptSelection(String.join("\n", asr), "ASR_FALLBACK");
    }

    private String chunkRef(Long mediaId, VideoChunk chunk) {
        return mediaId + ":" + analysisVersion(chunk) + ":" + chunk.startTime() + ":" + chunk.endTime();
    }

    private String analysisVersion(List<VideoChunk> chunks) {
        return chunks.isEmpty() || chunks.get(0).analysisVersion() == null
                ? "" : chunks.get(0).analysisVersion();
    }

    private String analysisVersion(VideoChunk chunk) {
        return chunk == null || chunk.analysisVersion() == null ? "" : chunk.analysisVersion();
    }

    private String cacheVersion(List<VideoChunk> chunks) {
        return analysisVersion(chunks) + "|" + properties.getIndexVersion()
                + "|" + properties.getBm25Version();
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) return 0;
        double dot = 0, leftLength = 0, rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        return leftLength == 0 || rightLength == 0 ? 0 : dot / Math.sqrt(leftLength * rightLength);
    }

    private record CachedIndex(String version, Bm25ChunkIndex index) { }
    private record RankedChunk(ReciprocalRankFusion.FusedChunk chunk, double score) { }
    private record TranscriptSelection(String text, String source) { }
}
