package com.example.server.service.ingest;

import com.example.server.config.MinuteRagProperties;
import com.example.server.service.MinuteRagIndexService;
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
 * One-shot minute-level index rebuild from the persisted V2 Context snapshot.
 * This is deliberately separate from the historical Chunk rebuild entrypoint.
 */
@Component
@ConditionalOnProperty(name = "rag.minute-index-rebuild.enabled", havingValue = "true")
public class MinuteRagIndexRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MinuteRagIndexRebuildRunner.class);

    private final MinuteRagIndexService indexService;
    private final MinuteRagProperties properties;
    private final ObjectMapper objectMapper;
    private final String mediaIds;
    private final String reportPath;

    public MinuteRagIndexRebuildRunner(MinuteRagIndexService indexService,
                                       MinuteRagProperties properties,
                                       ObjectMapper objectMapper,
                                       @Value("${rag.minute-index-rebuild.media-ids:${rag.index-rebuild.media-ids:}}") String mediaIds,
                                       @Value("${rag.minute-index-rebuild.report-path:.tmp/rag/minute-index-rebuild-report.json}") String reportPath) {
        this.indexService = indexService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.mediaIds = mediaIds;
        this.reportPath = reportPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Long> ids = parseMediaIds(mediaIds);
        if (ids.isEmpty()) throw new IllegalArgumentException("MINUTE_RAG_INDEX_REBUILD_MEDIA_IDS_REQUIRED");

        Instant startedAt = Instant.now();
        List<Map<String, Object>> results = new ArrayList<>();
        int totalDocuments = 0;
        int totalVectors = 0;
        boolean unavailable = !properties.isEnabled();
        for (Long mediaId : ids) {
            try {
                int vectors = indexService.rebuildIndexStrict(mediaId);
                int documents = indexService.documents(mediaId).size();
                totalDocuments += documents;
                totalVectors += vectors;
                results.add(result(mediaId, documents, vectors, "SUCCESS", null));
                log.info("minute_rag_index_rebuild_media_success mediaId={} documents={} vectors={}",
                        mediaId, documents, vectors);
            } catch (RuntimeException e) {
                unavailable = unavailable || isUnavailable(e);
                results.add(result(mediaId, 0, 0, unavailable ? "UNAVAILABLE" : "FAILED", diagnostic(e)));
                log.error("minute_rag_index_rebuild_media_failed mediaId={}", mediaId, e);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", startedAt.toString());
        report.put("completedAt", Instant.now().toString());
        report.put("mediaIds", ids);
        report.put("totalMedia", ids.size());
        report.put("totalDocuments", totalDocuments);
        report.put("totalDenseVectors", totalVectors);
        report.put("indexVersion", properties.getIndexVersion());
        report.put("bm25Version", properties.getBm25Version());
        report.put("denseCollection", properties.getDenseCollection());
        report.put("source", "media:context:v2");
        report.put("status", unavailable ? "UNAVAILABLE" : (results.stream().anyMatch(r -> "FAILED".equals(r.get("status"))) ? "FAILED" : "SUCCESS"));
        report.put("results", results);
        writeReport(Path.of(reportPath), report);
        if (unavailable) throw new IllegalStateException("MINUTE_RAG_INDEX_REBUILD_UNAVAILABLE");
        if (results.stream().anyMatch(r -> "FAILED".equals(r.get("status")))) {
            throw new IllegalStateException("MINUTE_RAG_INDEX_REBUILD_FAILED");
        }
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

    private Map<String, Object> result(Long mediaId, int documents, int vectors,
                                       String status, String error) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mediaId", mediaId);
        value.put("documents", documents);
        value.put("denseVectors", vectors);
        value.put("status", status);
        if (error != null) value.put("error", error);
        return value;
    }

    private boolean isUnavailable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("Embedding")
                    || message.contains("Qdrant")
                    || message.contains("MINUTE_RAG_DISABLED")
                    || message.contains("UNAVAILABLE"))) return true;
            current = current.getCause();
        }
        return false;
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
