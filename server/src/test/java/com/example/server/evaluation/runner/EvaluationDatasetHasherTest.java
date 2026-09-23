package com.example.server.evaluation.runner;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EvaluationDatasetHasherTest {

    @Test
    void ignoresDeclaredHashAndJsonObjectKeyOrder() {
        var mapper = JsonMapper.builder().build();
        byte[] first = """
                {"datasetVersion":"v1","provenance":{"datasetSha256":"old","judgeModel":"j"},"cases":[]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] second = """
                { "cases": [], "provenance": {"judgeModel":"j","datasetSha256":"new"}, "datasetVersion":"v1" }
                """.getBytes(StandardCharsets.UTF_8);

        assertEquals(EvaluationDatasetHasher.sha256(first, mapper),
                EvaluationDatasetHasher.sha256(second, mapper));
    }

    @Test
    void changesWhenDatasetContentChanges() {
        var mapper = JsonMapper.builder().build();
        byte[] first = "{\"datasetVersion\":\"v1\",\"provenance\":{},\"cases\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"datasetVersion\":\"v2\",\"provenance\":{},\"cases\":[]}"
                .getBytes(StandardCharsets.UTF_8);

        assertNotEquals(EvaluationDatasetHasher.sha256(first, mapper),
                EvaluationDatasetHasher.sha256(second, mapper));
    }
}
