package com.example.server.evaluation.runner;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationRunnerCliTest {

    @Test
    void parsesExplicitConfigDatasetAndOutput() {
        EvaluationRunnerCli.CliPaths paths = EvaluationRunnerCli.parsePaths(new String[] {
                "--config", "run.json", "--dataset=golden.json", "--output", "report.json"
        });

        assertEquals("run.json", paths.configPath());
        assertEquals("golden.json", paths.datasetPath());
        assertEquals("report.json", paths.outputPath());
    }

    @Test
    void requiresConfigAndRejectsUnknownOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> EvaluationRunnerCli.parsePaths(new String[] {"--dataset", "golden.json"}));
        assertThrows(IllegalArgumentException.class,
                () -> EvaluationRunnerCli.parsePaths(new String[] {"--mystery", "x"}));
    }

    @Test
    void usageFailureReturnsNonZeroWithoutStartingSpring() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = EvaluationRunnerCli.execute(
                new String[0], new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));

        assertEquals(2, exitCode);
    }
}
