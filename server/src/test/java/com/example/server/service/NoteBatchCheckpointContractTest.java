package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.NoteBatchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NoteBatchCheckpointContractTest {

    @Test
    void batchResultIdentityIncludesMediaProfileAndBatchId() {
        NoteBatchResult first = new NoteBatchResult(
                7L, "VIDEO_NOTE_V2", "note:7:VIDEO_NOTE_V2:no-chapter:0001", 1,
                new AnalysisResult("t", List.of("c"), List.of(), List.of(), List.of()), 1, true, "");

        assertEquals("7:VIDEO_NOTE_V2:note:7:VIDEO_NOTE_V2:no-chapter:0001", first.idempotencyKey());
        assertEquals(first.idempotencyKey(), NoteBatchResult.idempotencyKey(
                7L, "VIDEO_NOTE_V2", "note:7:VIDEO_NOTE_V2:no-chapter:0001"));
        assertNotEquals(first.idempotencyKey(), NoteBatchResult.idempotencyKey(
                7L, "VIDEO_NOTE_V1", "note:7:VIDEO_NOTE_V2:no-chapter:0001"));
    }
}
