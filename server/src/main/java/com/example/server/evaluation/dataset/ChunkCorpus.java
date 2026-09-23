package com.example.server.evaluation.dataset;

import com.example.server.evaluation.runner.EvaluationDataset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Judge 使用的只读字幕语料视图；只提取 transcript，不把 summary/OCR 当蕴含依据。 */
public record ChunkCorpus(List<Video> videos) {

    public ChunkCorpus {
        videos = videos == null ? List.of() : List.copyOf(videos);
    }

    public static ChunkCorpus read(ObjectMapper objectMapper, Path path) throws IOException {
        JsonNode root = objectMapper.readTree(path.toFile());
        JsonNode videoNodes = root.isArray() ? root : root.path("videos");
        if (!videoNodes.isArray()) throw new IllegalArgumentException("CHUNK_CORPUS_VIDEOS_REQUIRED");
        List<Video> videos = new ArrayList<>();
        for (JsonNode videoNode : videoNodes) {
            String mediaRef = videoNode.path("mediaRef").asText("").trim();
            if (mediaRef.isEmpty()) throw new IllegalArgumentException("CHUNK_CORPUS_MEDIA_REF_REQUIRED");
            List<Segment> segments = new ArrayList<>();
            for (JsonNode chunkNode : videoNode.path("chunks")) {
                for (JsonNode segmentNode : chunkNode.path("segments")) {
                    String transcript = segmentNode.path("transcript").asText("");
                    long startMs = segmentNode.path("startMs").asLong(-1);
                    long endMs = segmentNode.path("endMs").asLong(-1);
                    if (startMs >= 0 && endMs > startMs && !transcript.isBlank()) {
                        segments.add(new Segment(startMs, endMs, transcript));
                    }
                }
            }
            videos.add(new Video(mediaRef, segments));
        }
        return new ChunkCorpus(videos);
    }

    public String evidenceText(String mediaRef, List<EvaluationDataset.GoldEvidence> evidence) {
        Video video = videos.stream()
                .filter(candidate -> candidate.mediaRef().equalsIgnoreCase(mediaRef))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("CHUNK_CORPUS_MEDIA_REF_NOT_FOUND"));
        Set<String> texts = new LinkedHashSet<>();
        for (EvaluationDataset.GoldEvidence gold : evidence) {
            video.segments().stream()
                    .filter(segment -> overlaps(gold.startMs(), gold.endMs(), segment.startMs(), segment.endMs()))
                    .map(Segment::transcript)
                    .map(String::trim)
                    .filter(text -> !text.isBlank())
                    .forEach(texts::add);
        }
        return String.join("\n", texts);
    }

    private boolean overlaps(long firstStart, long firstEnd, long secondStart, long secondEnd) {
        return Math.max(firstStart, secondStart) < Math.min(firstEnd, secondEnd);
    }

    public record Video(String mediaRef, List<Segment> segments) {
        public Video {
            if (mediaRef == null || mediaRef.isBlank()) {
                throw new IllegalArgumentException("CHUNK_CORPUS_MEDIA_REF_REQUIRED");
            }
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }

    public record Segment(long startMs, long endMs, String transcript) {
        public Segment {
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("CHUNK_SEGMENT_RANGE_INVALID");
            transcript = transcript == null ? "" : transcript.trim();
        }
    }
}
