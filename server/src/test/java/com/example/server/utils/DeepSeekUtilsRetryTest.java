package com.example.server.utils;

import dev.langchain4j.exception.NonRetriableException;
import org.junit.jupiter.api.Test;

import java.io.EOFException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekUtilsRetryTest {

    @Test
    void eofWhileReadingModelResponseIsRetriable() {
        assertTrue(DeepSeekUtils.isRetriableModelFailure(
                new IllegalStateException("模型调用失败", new EOFException("EOF reached while reading"))));
    }

    @Test
    void nonRetriableFailureStillWinsOverNestedIoException() {
        assertFalse(DeepSeekUtils.isRetriableModelFailure(
                new NonRetriableException("invalid request", new EOFException("EOF"))));
    }
}
