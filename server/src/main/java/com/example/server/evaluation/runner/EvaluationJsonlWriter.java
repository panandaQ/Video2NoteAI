package com.example.server.evaluation.runner;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Incremental evaluation output. Each line is a self-contained JSON event so a
 * long run can be tailed and a partial file remains useful after a process crash.
 */
public final class EvaluationJsonlWriter implements EvaluationProgressSink, AutoCloseable {
    private final ObjectMapper objectMapper;
    private final BufferedWriter writer;

    public EvaluationJsonlWriter(ObjectMapper objectMapper, Path output) {
        try {
            Path absolute = output.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            this.objectMapper = objectMapper;
            this.writer = Files.newBufferedWriter(absolute,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open evaluation JSONL output", e);
        }
    }

    @Override
    public void onRunStarted(EvaluationReport.Metadata metadata,
                             Map<String, String> metricDefinitions) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "run_started");
        event.put("metadata", metadata);
        event.put("metricDefinitions", metricDefinitions);
        write(event);
    }

    @Override
    public void onTurn(EvaluationVariant variant, String conversationCaseId,
                       EvaluationReport.TurnResult result) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "turn_completed");
        event.put("variant", variant);
        event.put("conversationCaseId", conversationCaseId);
        event.put("result", result);
        write(event);
    }

    @Override
    public void onCaseCompleted(EvaluationVariant variant, EvaluationReport.CaseResult result) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "case_completed");
        event.put("variant", variant);
        event.put("conversationCaseId", result.conversationCaseId());
        event.put("mediaRef", result.mediaRef());
        event.put("resolvedUserId", result.resolvedUserId());
        event.put("resolvedMediaId", result.resolvedMediaId());
        event.put("summary", result.summary());
        write(event);
    }

    @Override
    public void onVariantCompleted(EvaluationVariant variant,
                                   EvaluationReport.VariantSummary summary) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "variant_completed");
        event.put("variant", variant);
        event.put("summary", summary);
        write(event);
    }

    @Override
    public void onRunCompleted(EvaluationReport.Metadata metadata,
                               Map<EvaluationVariant, EvaluationReport.VariantSummary> summaries) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "run_completed");
        event.put("metadata", metadata);
        event.put("summaries", summaries);
        write(event);
    }

    private synchronized void write(Map<String, Object> event) {
        try {
            writer.write(objectMapper.writeValueAsString(event));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write evaluation JSONL output", e);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close evaluation JSONL output", e);
        }
    }
}
