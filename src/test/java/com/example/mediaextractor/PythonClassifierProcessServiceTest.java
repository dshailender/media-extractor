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
    void testBuildCommandWithReviewLinkOptions() {
        Path python = Path.of(".venv/bin/python");
        Path script = Path.of("scripts/classify_memes.py");
        Path memoriesDir = Path.of("/home/user/memories");
        Path manifest = Path.of("/tmp/test.manifest");

        MockEnvironment envDefault = new MockEnvironment();
        List<String> cmdDefault = PythonClassifierProcessService.buildCommand(
                python, script, memoriesDir, manifest, "move", true, envDefault
        );
        assertFalse(cmdDefault.contains("--no-review-links"));
        assertFalse(cmdDefault.contains("--review-link-type"));

        MockEnvironment envCustom = new MockEnvironment();
        envCustom.setProperty("media-extractor.classifier-create-review-links", "false");
        envCustom.setProperty("media-extractor.classifier-review-link-type", "shortcut");
        List<String> cmdCustom = PythonClassifierProcessService.buildCommand(
                python, script, memoriesDir, manifest, "move", true, envCustom
        );
        assertTrue(cmdCustom.contains("--no-review-links"));
        assertTrue(cmdCustom.contains("--review-link-type"));
        assertTrue(cmdCustom.contains("shortcut"));
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

    @Test
    void testFormatSeconds() {
        assertEquals("< 10s", PythonClassifierProcessService.formatSeconds(5));
        assertEquals("1m 15s", PythonClassifierProcessService.formatSeconds(75));
        assertEquals("1h 1m", PythonClassifierProcessService.formatSeconds(3700));
        assertEquals("1h", PythonClassifierProcessService.formatSeconds(3600));
    }

    @Test
    void testCandidateDiscoveryFallbackExcludesNonPhotos(@org.junit.jupiter.api.io.TempDir Path tempDir) throws java.io.IOException {
        Path memoriesDir = tempDir.resolve("memories");
        Path y2024Photos = memoriesDir.resolve("2024").resolve("photos");
        Path y2023Photos = memoriesDir.resolve("2023").resolve("photos");
        Path staging = memoriesDir.resolve(".staging-12345");
        Path videos = memoriesDir.resolve("2024").resolve("videos");
        Path state = memoriesDir.resolve(".classifier-state");

        java.nio.file.Files.createDirectories(y2024Photos);
        java.nio.file.Files.createDirectories(y2023Photos);
        java.nio.file.Files.createDirectories(staging);
        java.nio.file.Files.createDirectories(videos);
        java.nio.file.Files.createDirectories(state);

        Path img1 = y2024Photos.resolve("img1.jpg");
        Path img2 = y2023Photos.resolve("img2.png");
        Path stagedImg = staging.resolve("staged.jpg");
        Path videoFile = videos.resolve("clip.mp4");
        Path stateFile = state.resolve("dummy.jpg");

        java.nio.file.Files.writeString(img1, "img1");
        java.nio.file.Files.writeString(img2, "img2");
        java.nio.file.Files.writeString(stagedImg, "staged");
        java.nio.file.Files.writeString(videoFile, "video");
        java.nio.file.Files.writeString(stateFile, "state");

        PythonClassifierProcessService service = new PythonClassifierProcessService();
        PythonClassifierProcessService.CandidateDiscoveryResult result = service.discoverCandidatesFallback(memoriesDir, List.of(), List.of());
        List<Path> discovered = result.candidatePaths();
        assertEquals(2, discovered.size());
        assertTrue(discovered.contains(img1.toAbsolutePath().normalize()));
        assertTrue(discovered.contains(img2.toAbsolutePath().normalize()));
        assertFalse(discovered.contains(stagedImg.toAbsolutePath().normalize()));
        assertFalse(discovered.contains(videoFile.toAbsolutePath().normalize()));
        assertFalse(discovered.contains(stateFile.toAbsolutePath().normalize()));
        assertEquals(3.8, result.secondsPerImage());
        assertEquals("calibrated_baseline", result.rateSource());
        assertEquals("< 10s", result.estimatedTimeFormatted());
    }

    @Test
    void testEmptyExtractionDiscoversAndResumesRemainingPhotos(@org.junit.jupiter.api.io.TempDir Path tempDir) throws java.io.IOException {
        Path memoriesDir = tempDir.resolve("memories");
        Path y2024Photos = memoriesDir.resolve("2024").resolve("photos");
        java.nio.file.Files.createDirectories(y2024Photos);

        Path pendingPhoto = y2024Photos.resolve("pending.png");
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(400, 400, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(img, "png", pendingPhoto.toFile());

        PythonClassifierProcessService service = new PythonClassifierProcessService();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("media-extractor.classifier-enabled", "true");
        env.setProperty("media-extractor.classifier-mode", "java-only");

        // Pass empty extractedImagePaths (simulating a re-run where mediaExtractor found 0 new files)
        service.classifyAfterExtraction(memoriesDir, List.of(), env);

        // In java-only mode, the pending photo without EXIF camera hardware should have been triaged!
        // Staged files were cleaned up or restored because python was not invoked.
        assertTrue(java.nio.file.Files.exists(pendingPhoto));
    }
}

