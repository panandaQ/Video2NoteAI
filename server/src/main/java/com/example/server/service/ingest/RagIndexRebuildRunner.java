package com.example.server.service.ingest;

import com.example.server.dto.VideoChunk;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.HybridChunkRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-shot retrieval-index rebuild. It only reads persisted Context/Chunk checkpoints;
 * it never downloads media, subtitles or OCR artifacts and never deletes the old index.
 */
@Component
@ConditionalOnProperty(name = "rag.index-rebuild.enabled", havingValue = "true")
public class RagIndexRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagIndexRebuildRunner.class);

    private final AgentCheckpointService checkpointService;
    private final HybridChunkRetrievalService retrievalService;
    private final ObjectMapper objectMapper;
    private final String mediaIds;
    private final String reportPath;

    public RagIndexRebuildRunner(AgentCheckpointService checkpointService,
                                 HybridChunkRetrievalService retrievalService,
                                 ObjectMapper objectMapper,
                                 @Value("${rag.index-rebuild.media-ids:}") String mediaIds,
                                 @Value("${rag.index-rebuild.report-path:.tmp/rag/index-rebuild-report.json}") String reportPath) {
        this.checkpointService = checkpointService;
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
        this.mediaIds = mediaIds;
        this.reportPath = reportPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Long> ids = parseMediaIds(mediaIds);
        if (ids.isEmpty()) throw new IllegalArgumentException("RAG_INDEX_REBUILD_MEDIA_IDS_REQUIRED");

        Instant startedAt = Instant.now();
        List<Map<String, Object>> results = new ArrayList<>();
        int totalChunks = 0;
        int totalVectors = 0;
        boolean failed = false;
        for (Long mediaId : ids) {
            try {
                List<VideoChunk> chunks = checkpointService.loadChunks(mediaId);
                if (chunks == null || chunks.isEmpty()) {
                    throw new IllegalStateException("CHUNK_SNAPSHOT_NOT_FOUND");
                }
                int vectors = retrievalService.rebuildIndex(mediaId, chunks);
                totalChunks += chunks.size();
                totalVectors += vectors;
                results.add(result(mediaId, chunks.size(), vectors, "SUCCESS", null));
                log.info("rag_index_rebuild_media_success mediaId={} chunks={} vectors={}",
                        mediaId, chunks.size(), vectors);
            } catch (RuntimeException e) {
                failed = true;
                results.add(result(mediaId, 0, 0, "FAILED", diagnostic(e)));
                log.error("rag_index_rebuild_media_failed mediaId={}", mediaId, e);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", startedAt.toString());
        report.put("completedAt", Instant.now().toString());
        report.put("mediaIds", ids);
        report.put("totalMedia", ids.size());
        report.put("totalChunks", totalChunks);
        report.put("totalDenseVectors", totalVectors);
        report.put("status", failed ? "FAILED" : "SUCCESS");
        report.put("results", results);
        writeReport(Path.of(reportPath), report);
        if (failed) throw new IllegalStateException("RAG_INDEX_REBUILD_FAILED");
    }

    private List<Long> parseMediaIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(Long::valueOf)
                .distinct()
                .toList();
    }

    private Map<String, Object> result(Long mediaId, int chunks, int vectors, String status, String error) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mediaId", mediaId);
        value.put("chunks", chunks);
        value.put("denseVectors", vectors);
        value.put("status", status);
        if (error != null) value.put("error", error);
        return value;
    }

    private String diagnostic(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) return current.getMessage();
            current = current.getCause();
        }
        return error.getClass().getSimpleName();
    }

    private void writeReport(Path output, Object report) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), report);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
