package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleLogTest {

    @Test
    void summarizesAndRedactsSensitiveToolArguments() {
        String summary = ConsoleLog.summary("{\"token\":\"should-not-appear\",\"query\":\"天气\"}");

        assertFalse(summary.contains("should-not-appear"));
        assertTrue(summary.contains("***"));
        assertTrue(summary.contains("天气"));
    }

    @Test
    void truncatesLargeToolOutput() {
        String summary = ConsoleLog.summary("x".repeat(500));

        assertTrue(summary.length() <= 243);
        assertTrue(summary.endsWith("..."));
    }
}
