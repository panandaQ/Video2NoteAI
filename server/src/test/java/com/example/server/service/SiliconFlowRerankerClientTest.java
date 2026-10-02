package com.example.server.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SiliconFlowRerankerClientTest {

    @Test
    void parsesSiliconFlowOfficialResultsEnvelope() {
        String response = """
                {
                  "id": "rerank-test",
                  "results": [
                    {"index": 1, "relevance_score": 0.42},
                    {"index": 0, "relevance_score": 0.91}
                  ]
                }
                """;

        List<SiliconFlowRerankerClient.RerankResult> results =
                SiliconFlowRerankerClient.parseResults(response, 2, 2);

        assertEquals(List.of(0, 1), results.stream()
                .map(SiliconFlowRerankerClient.RerankResult::index)
                .toList());
        assertEquals(0.91D, results.get(0).score());
    }
}
