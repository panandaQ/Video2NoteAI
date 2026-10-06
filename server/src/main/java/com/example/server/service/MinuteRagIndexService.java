package com.example.server.service;

import com.example.server.config.MinuteRagProperties;
import com.example.server.dto.SegmentRetrievalDocument;
import com.example.server.dto.VideoContext;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds and caches the independent minute index from the persisted V2 Context. */
@Service
public class MinuteRagIndexService {
    private final AgentCheckpointService checkpointService;
    private final SegmentDocumentBuilder documentBuilder;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;
    private final MinuteRagProperties properties;
    private final AgentTelemetry telemetry;
    private final Map<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    public MinuteRagIndexService(AgentCheckpointService checkpointService,
                                 SegmentDocumentBuilder documentBuilder,
                                 EmbeddingUtils embeddingUtils,
                                 QdrantVectorStore vectorStore,
                                 MinuteRagProperties properties,
                                 AgentTelemetry telemetry) {
        this.checkpointService = checkpointService;
        this.documentBuilder = documentBuilder;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.telemetry = telemetry;
    }

    /** Reads only {@code media:context:v2}; no video/subtitle/OCR acquisition is performed. */
    public List<SegmentRetrievalDocument> documents(Long mediaId) {
        if (mediaId == null) return List.of();
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) return List.of();
        String fingerprint = fingerprint(context);
        Snapshot cached = snapshots.get(mediaId);
        if (cached != null && cached.fingerprint().equals(fingerprint)) return cached.documents();
        List<SegmentRetrievalDocument> documents = documentBuilder.build(mediaId, context);
        snapshots.put(mediaId, new Snapshot(fingerprint, documents));
        return documents;
    }

    /** Idempotent rebuild/upsert. Stable segmentRef and independent collection enable rollback. */
    public int index(Long mediaId) {
        if (!properties.isEnabled()) return 0;
        List<SegmentRetrievalDocument> documents = documents(mediaId);
        if (documents.isEmpty()) return 0;
        List<List<Double>> vectors = new ArrayList<>(documents.size());
        for (SegmentRetrievalDocument document : documents) {
            if (document.denseText().isBlank()) vectors.add(List.of());
            else {
                try { vectors.add(embeddingUtils.embed(document.denseText())); }
                catch (RuntimeException e) { telemetry.incrementCurrent("minuteDenseDocumentFallbacks", 1); vectors.add(List.of()); }
            }
        }
        try {
            vectorStore.upsertSegmentDense(mediaId, documents, vectors,
                    properties.getDenseCollection(), properties.getIndexVersion());
        } catch (RuntimeException e) {
            // The context-backed BM25/local dense path remains usable while the new collection is unavailable.
            telemetry.incrementCurrent("minuteDenseWriteFallbacks", 1);
        }
        int written = (int) vectors.stream().filter(vector -> vector != null && !vector.isEmpty()).count();
        telemetry.incrementCurrent("minuteIndexDocuments", documents.size());
        telemetry.incrementCurrent("minuteDenseWrites", written);
        return written;
    }

    /**
     * Strict one-shot rebuild used by offline validation only.
     *
     * <p>The online path intentionally degrades to the Context/BM25 path when
     * BGE-M3 or Qdrant is unavailable.  An index rebuild must not turn that
     * degradation into a successful-looking report, so this method propagates
     * either external failure and only returns after the dense upsert succeeds.
     */
    public int rebuildIndexStrict(Long mediaId) {
        if (!properties.isEnabled()) throw new IllegalStateException("MINUTE_RAG_DISABLED");
        List<SegmentRetrievalDocument> documents = documents(mediaId);
        if (documents.isEmpty()) throw new IllegalStateException("CONTEXT_SNAPSHOT_NOT_FOUND");
        List<List<Double>> vectors = new ArrayList<>(documents.size());
        for (SegmentRetrievalDocument document : documents) {
            if (document.denseText().isBlank()) {
                vectors.add(List.of());
            } else {
                vectors.add(embeddingUtils.embed(document.denseText()));
            }
        }
        int written = (int) vectors.stream().filter(vector -> vector != null && !vector.isEmpty()).count();
        if (written == 0) throw new IllegalStateException("MINUTE_EMBEDDING_UNAVAILABLE");
        vectorStore.upsertSegmentDense(mediaId, documents, vectors,
                properties.getDenseCollection(), properties.getIndexVersion());
        telemetry.incrementCurrent("minuteIndexDocuments", documents.size());
        telemetry.incrementCurrent("minuteDenseWrites", written);
        return written;
    }

    public void evict(Long mediaId) { if (mediaId != null) snapshots.remove(mediaId); }

    private String fingerprint(VideoContext context) {
        return String.valueOf(context.analysisVersion()) + ":" + context.segments().stream()
                .map(segment -> segment.startMs() + ":" + segment.endMs())
                .reduce("", String::concat);
    }

    private record Snapshot(String fingerprint, List<SegmentRetrievalDocument> documents) { }
}
