package com.example.server.dto;

import java.util.List;

/** Versioned lexical/dense index document derived from one persisted five-minute Chunk. */
public record ChunkRetrievalDocument(
        String chunkRef,
        Long mediaId,
        String analysisVersion,
        String indexVersion,
        String bm25Version,
        long startMs,
        long endMs,
        String summary,
        List<String> keywords,
        List<String> originalTerms,
        String transcript,
        String transcriptSource
) {
    public ChunkRetrievalDocument {
        chunkRef = chunkRef == null ? "" : chunkRef;
        analysisVersion = analysisVersion == null ? "" : analysisVersion;
        indexVersion = indexVersion == null ? "" : indexVersion;
        bm25Version = bm25Version == null ? "" : bm25Version;
        summary = summary == null ? "" : summary;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        originalTerms = originalTerms == null ? List.of() : List.copyOf(originalTerms);
        transcript = transcript == null ? "" : transcript;
        transcriptSource = transcriptSource == null || transcriptSource.isBlank()
                ? "NONE" : transcriptSource;
    }

    public ChunkRetrievalDocument(String chunkRef, Long mediaId, String analysisVersion,
                                  String indexVersion, String bm25Version, long startMs, long endMs,
                                  String summary, List<String> keywords, List<String> originalTerms,
                                  String transcript) {
        this(chunkRef, mediaId, analysisVersion, indexVersion, bm25Version, startMs, endMs,
                summary, keywords, originalTerms, transcript, "UNKNOWN");
    }

    public String lexicalText() {
        return String.join(" ", summary, String.join(" ", keywords),
                String.join(" ", originalTerms));
    }
}
