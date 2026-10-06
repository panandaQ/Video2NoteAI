package com.example.server.dto;

import java.util.List;

/** Stable minute-level retrieval document derived directly from a persisted VideoContext segment. */
public record SegmentRetrievalDocument(
        Long mediaId,
        String segmentRef,
        String chapterId,
        String chapterTitle,
        long startMs,
        long endMs,
        String transcript,
        String transcriptSource,
        List<String> ocrTexts,
        List<String> evidenceFrames,
        String analysisVersion,
        String indexVersion,
        String bm25Version
) {
    public SegmentRetrievalDocument {
        if (mediaId == null) throw new IllegalArgumentException("mediaId is required");
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid segment range");
        segmentRef = segmentRef == null || segmentRef.isBlank()
                ? mediaId + ":" + startMs + ":" + endMs : segmentRef;
        transcript = transcript == null ? "" : transcript.trim();
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        evidenceFrames = evidenceFrames == null ? List.of() : List.copyOf(evidenceFrames);
        analysisVersion = analysisVersion == null || analysisVersion.isBlank()
                ? VideoContext.ANALYSIS_VERSION_V2 : analysisVersion;
        indexVersion = indexVersion == null ? "" : indexVersion;
        transcriptSource = transcriptSource == null || transcriptSource.isBlank() ? "ASR" : transcriptSource;
        bm25Version = bm25Version == null ? "" : bm25Version;
    }

    public String lexicalText() {
        return String.join(" ", transcript, String.join(" ", ocrTexts),
                chapterTitle == null ? "" : chapterTitle);
    }

    public String denseText() {
        return lexicalText();
    }

    public VideoContext.VideoSegment toSegment() {
        TranscriptSource source = "CC".equalsIgnoreCase(transcriptSource)
                ? TranscriptSource.CC : TranscriptSource.ASR;
        return new VideoContext.VideoSegment(startMs, endMs, transcript, ocrTexts,
                evidenceFrames, source, chapterId);
    }
}
