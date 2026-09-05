package com.example.mediaextractor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonClassifierProcessServiceTest {

    @Test
    void testBuildCommandWithQuarantineEnabled() {
        Path python = Path.of(".venv/bin/python");
        Path script = Path.of("scripts/classify_memes.py");
        Path memoriesDir = Path.of("/home/user/memories");
        Path manifest = Path.of("/tmp/test.manifest");
        MockEnvironment env = new MockEnvironment();

        List<String> command = PythonClassifierProcessService.buildCommand(
                python, script, memoriesDir, manifest, "move", true, env
        );

        assertTrue(command.contains("--action"));
        assertTrue(command.contains("move"));
        assertTrue(command.contains("--quarantine"));
        assertTrue(command.contains("--input-manifest"));
        assertTrue(command.contains(manifest.toString()));
    }

    @Test
    void testBuildCommandWithQuarantineDisabled() {
        Path python = Path.of(".venv/bin/python");
        Path script = Path.of("scripts/classify_memes.py");
        Path memoriesDir = Path.of("/home/user/memories");
        Path manifest = Path.of("/tmp/test.manifest");
        MockEnvironment env = new MockEnvironment();

        List<String> command = PythonClassifierProcessService.buildCommand(
                python, script, memoriesDir, manifest, "dry-run", false, env
        );

        assertTrue(command.contains("--action"));
        assertTrue(command.contains("dry-run"));
        assertFalse(command.contains("--quarantine"));
    }

    @Test
    void testBuildCommandWithOptionalCsvOutputs() {
        Path python = Path.of(".venv/bin/python");
        Path script = Path.of("scripts/classify_memes.py");
        Path memoriesDir = Path.of("/home/user/memories");
        Path manifest = Path.of("/tmp/test.manifest");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("media-extractor.classifier-output-csv", "output.csv");
        env.setProperty("media-extractor.classifier-review-csv", "review.csv");

        List<String> command = PythonClassifierProcessService.buildCommand(
                python, script, memoriesDir, manifest, "move", true, env
        );

        assertTrue(command.contains("--output-csv"));
        assertTrue(command.contains("output.csv"));
        assertTrue(command.contains("--review-csv"));
        assertTrue(command.contains("review.csv"));
    }
}

