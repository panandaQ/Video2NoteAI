package com.example.server.evaluation.runner;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationRunConfigTest {

    @Test
    void defaultsToAllAblationVariants() throws Exception {
        EvaluationRunConfig config = JsonMapper.builder().build().readValue("""
                {"datasetPath":"golden.json","outputPath":"report.json"}
                """, EvaluationRunConfig.class);

        assertEquals(List.of(EvaluationVariant.A, EvaluationVariant.B,
                EvaluationVariant.C, EvaluationVariant.D), config.variants());
    }

    @Test
    void rejectsMissingPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationRunConfig(null, null, null, null, null,
                        null, null, null, null));
    }
}
