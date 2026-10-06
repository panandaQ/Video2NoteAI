package com.example.server.service.ingest;

import com.example.server.config.MinuteRagProperties;
import com.example.server.dto.SegmentRetrievalDocument;
import com.example.server.service.MinuteRagIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinuteRagIndexRebuildRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void unavailableEmbeddingIsReportedWithoutSuccessMetrics() throws Exception {
        MinuteRagIndexService index = mock(MinuteRagIndexService.class);
        MinuteRagProperties properties = enabledProperties();
        when(index.rebuildIndexStrict(62L)).thenThrow(new IllegalStateException("Embedding API failed: 401"));
        when(index.documents(62L)).thenReturn(List.of(document()));
        Path report = tempDir.resolve("minute.json");
        MinuteRagIndexRebuildRunner runner = new MinuteRagIndexRebuildRunner(
                index, properties, new ObjectMapper(), "62", report.toString());

        assertThrows(IllegalStateException.class, () -> runner.run(null));
        var json = new ObjectMapper().readTree(report.toFile());
        assertEquals("UNAVAILABLE", json.get("status").asText());
        assertEquals(0, json.get("totalDenseVectors").asInt());
        assertEquals("UNAVAILABLE", json.get("results").get(0).get("status").asText());
    }

    @Test
    void successfulRebuildReportsContextSourceAndDenseVectors() throws Exception {
        MinuteRagIndexService index = mock(MinuteRagIndexService.class);
        MinuteRagProperties properties = enabledProperties();
        when(index.rebuildIndexStrict(62L)).thenReturn(1);
        when(index.documents(62L)).thenReturn(List.of(document()));
        Path report = tempDir.resolve("minute-success.json");
        MinuteRagIndexRebuildRunner runner = new MinuteRagIndexRebuildRunner(
                index, properties, new ObjectMapper(), "62", report.toString());

        runner.run(null);
        var json = new ObjectMapper().readTree(report.toFile());
        assertEquals("SUCCESS", json.get("status").asText());
        assertEquals("media:context:v2", json.get("source").asText());
        assertEquals(1, json.get("totalDocuments").asInt());
        assertEquals(1, json.get("totalDenseVectors").asInt());
    }

    private MinuteRagProperties enabledProperties() {
        MinuteRagProperties properties = new MinuteRagProperties();
        properties.setEnabled(true);
        return properties;
    }

    private SegmentRetrievalDocument document() {
        return new SegmentRetrievalDocument(62L, "62:0:60000", null, null,
                0, 60000, "字幕", "CC", List.of(), List.of(), "VIDEO_CONTEXT_V2",
                "RAG_MINUTE_V1", "BM25_SEGMENT_V1");
    }
}
