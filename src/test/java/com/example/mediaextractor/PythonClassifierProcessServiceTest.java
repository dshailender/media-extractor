package com.example.mediaextractor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void testClassifierOptionsDefaultsAndOverrides() {
        PythonClassifierProcessService.ClassifierOptions defaults = PythonClassifierProcessService.ClassifierOptions.defaults();
        assertFalse(Boolean.TRUE.equals(defaults.enabledOverride()));
        assertFalse("move".equals(defaults.actionOverride()));

        PythonClassifierProcessService.ClassifierOptions custom = new PythonClassifierProcessService.ClassifierOptions(
                true, "dry-run", false, "java-triage-python"
        );
        assertTrue(custom.enabledOverride());
        assertEquals("dry-run", custom.actionOverride());
        assertFalse(custom.quarantineOverride());
        assertEquals("java-triage-python", custom.modeOverride());
    }

    @Test
    void testClassifyAfterExtractionWhenDisabledDoesNotThrow() {
        PythonClassifierProcessService service = new PythonClassifierProcessService();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("media-extractor.classifier-enabled", "false");

        service.classifyAfterExtraction(Path.of("/tmp/memories"), List.of(Path.of("/tmp/photo.jpg")), env);
    }

    @Test
    void testBuildCommandWithStateDbAndResumeOptions() {
        Path python = Path.of(".venv/bin/python");
        Path script = Path.of("scripts/classify_memes.py");
        Path memoriesDir = Path.of("/home/user/memories");
        Path manifest = Path.of("/tmp/test.manifest");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("media-extractor.classifier-state-db", "/custom/state.sqlite3");
        env.setProperty("media-extractor.classifier-resume", "false");
        env.setProperty("media-extractor.classifier-fingerprint-strategy", "full-sha256");
        env.setProperty("media-extractor.classifier-lease-timeout-seconds", "120");

        List<String> command = PythonClassifierProcessService.buildCommand(
                python, script, memoriesDir, manifest, "move", true, env
        );

        assertTrue(command.contains("--state-db"));
        assertTrue(command.contains("/custom/state.sqlite3"));
        assertTrue(command.contains("--no-resume"));
        assertTrue(command.contains("--fingerprint-strategy"));
        assertTrue(command.contains("full-sha256"));
        assertTrue(command.contains("--lease-timeout"));
        assertTrue(command.contains("120"));
    }

    @Test
    void testStaleStagingRecoveryInvokedOnClassify(@org.junit.jupiter.api.io.TempDir Path tempDir) throws java.io.IOException {
        Path memoriesDir = tempDir.resolve("memories");
        Path stagingDir = memoriesDir.resolve(".staging-20240901_120000_recoverme");
        java.nio.file.Files.createDirectories(stagingDir);
        Path stagedFile = stagingDir.resolve("test_staged.jpg");
        java.nio.file.Files.writeString(stagedFile, "staged-data");

        Path originalPhoto = memoriesDir.resolve("2024").resolve("photos").resolve("test_staged.jpg");
        Path manifest = stagingDir.resolve("manifest.jsonl");
        java.nio.file.Files.writeString(manifest, String.format(
                "{\"staged_path\":\"%s\",\"original_path\":\"%s\",\"original_year\":2024,\"triage_category\":\"NEEDS_PYTHON\",\"triage_reason\":\"test\"}%n",
                stagedFile.toAbsolutePath().normalize().toString().replace("\\", "\\\\"),
                originalPhoto.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        ));

        PythonClassifierProcessService service = new PythonClassifierProcessService();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("media-extractor.classifier-enabled", "true");
        env.setProperty("media-extractor.classifier-mode", "java-only");
        env.setProperty("media-extractor.classifier-stale-staging-recovery", "true");

        service.classifyAfterExtraction(memoriesDir, List.of(), env);

        // Verify orphan staged file was recovered back to photos!
        assertTrue(java.nio.file.Files.exists(originalPhoto));
        assertEquals("staged-data", java.nio.file.Files.readString(originalPhoto));
    }
}

