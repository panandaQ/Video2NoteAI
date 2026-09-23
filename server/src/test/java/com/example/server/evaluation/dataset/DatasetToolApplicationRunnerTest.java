package com.example.server.evaluation.dataset;

import com.example.server.evaluation.runner.EvaluationDataset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetToolApplicationRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChunkDraftExporter exporter = mock(ChunkDraftExporter.class);
    private final RetrievalSelfCheckService retrieval = mock(RetrievalSelfCheckService.class);
    private final QuestionDeduplicationService deduplication = mock(QuestionDeduplicationService.class);
    private final DatasetToolApplicationRunner runner =
            new DatasetToolApplicationRunner(objectMapper, exporter, retrieval, deduplication);

    @Test
    void exportChunksWritesExistingTopLevelArrayWithoutMediaId(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("request.json");
        Path output = tempDir.resolve("chunks.json");
        Files.writeString(input, "{\"media\":[{\"mediaId\":62,\"sourceVideoTag\":\"video-A\"}]}");
        ChunkDraftExporter.ExportedVideo video = new ChunkDraftExporter.ExportedVideo(
                "sha256:abc", "video-A", "title", "CC", 10L, 0, 0, List.of());
        when(exporter.export(any())).thenReturn(new ChunkDraftExporter.ExportBundle(
                "chunk-draft-v1", List.of(video)));

        runner.run(arguments("export-chunks", input, output));

        JsonNode json = objectMapper.readTree(output.toFile());
        assertTrue(json.isArray());
        assertFalse(json.get(0).has("mediaId"));
        assertTrue(json.get(0).path("mediaRef").asText().startsWith("sha256:"));
    }

    @Test
    void failedRetrievalGateWritesReportBeforeReturningNonZeroSignal(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("dataset.json");
        Path output = tempDir.resolve("report.json");
        objectMapper.writeValue(input.toFile(), dataset());
        RetrievalSelfCheckService.Report report = new RetrievalSelfCheckService.Report(
                "retrieval-self-check-v1", 5, false, List.of());
        when(retrieval.check(any(), eq(5))).thenReturn(report);

        DatasetGateFailedException error = assertThrows(DatasetGateFailedException.class,
                () -> runner.run(arguments("check-retrieval", input, output)));

        assertTrue(Files.isRegularFile(output));
        assertFalse(objectMapper.readTree(output.toFile()).path("passed").asBoolean());
        assertTrue(error.getMessage().contains("RETRIEVAL_GATE_FAILED"));
    }

    private DefaultApplicationArguments arguments(String command, Path input, Path output) {
        return new DefaultApplicationArguments(
                "--evaluation.dataset.command=" + command,
                "--evaluation.dataset.input=" + input,
                "--evaluation.dataset.output=" + output);
    }

    private EvaluationDataset dataset() {
        EvaluationDataset.GoldenTurn turn = new EvaluationDataset.GoldenTurn(
                1, "首轮直接问题", "问题", "独立问题", true, List.of("要点"),
                List.of(new EvaluationDataset.GoldEvidence(1, 2, "CC")));
        return new EvaluationDataset("v1", new EvaluationDataset.Provenance(
                "generator", "gen-v1", "judge", "judge-v1", "production", null, null),
                List.of(new EvaluationDataset.ConversationCase(
                        "case-1", "sha256:abc", "video-A", "CC", List.of(turn))));
    }
}
