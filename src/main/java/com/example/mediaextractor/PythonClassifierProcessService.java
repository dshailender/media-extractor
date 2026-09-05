package com.example.mediaextractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class PythonClassifierProcessService {

    private static final Logger log = LoggerFactory.getLogger(PythonClassifierProcessService.class);
    private static final long DEFAULT_TIMEOUT_MINUTES = 1_440L;

    public void classifyAfterExtraction(Path memoriesDir, List<Path> imagePaths, Environment environment) {
        boolean enabled = environment.getProperty("media-extractor.classifier-enabled", Boolean.class, false);
        if (!enabled) {
            return;
        }
        if (imagePaths.isEmpty()) {
            log.info("Python classifier enabled, but no images were extracted in this run");
            return;
        }

        Path workingDirectory = resolvePath(environment.getProperty("media-extractor.classifier-working-directory", ""), Path.of("."));
        Path python = resolvePath(environment.getProperty("media-extractor.classifier-python", ".venv/bin/python"), workingDirectory);
        Path script = resolvePath(environment.getProperty("media-extractor.classifier-script", "scripts/classify_memes.py"), workingDirectory);
        String action = environment.getProperty("media-extractor.classifier-action", "dry-run");
        if (!List.of("dry-run", "copy", "move").contains(action)) {
            log.error("Invalid classifier action '{}'; expected dry-run, copy, or move", action);
            return;
        }

        Path manifest = null;
    Process process = null;
        try {
            manifest = Files.createTempFile("media-extractor-classifier-", ".manifest");
            Files.write(manifest, imagePaths.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList(),
                    StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>(List.of(
                    python.toString(), script.toString(),
                    "--source-dir", memoriesDir.toAbsolutePath().normalize().toString(),
                    "--input-manifest", manifest.toString(),
                    "--action", action));
            addOptionalArgument(command, environment, "media-extractor.classifier-output-csv", "--output-csv");
            addOptionalArgument(command, environment, "media-extractor.classifier-review-csv", "--review-csv");

            log.info("Starting Python classifier for {} extracted images using {}", imagePaths.size(), script);
                Process startedProcess = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
                process = startedProcess;

                CompletableFuture<Void> outputDrainer = CompletableFuture.runAsync(() -> drainOutput(startedProcess));

            long timeoutMinutes = environment.getProperty("media-extractor.classifier-timeout-minutes", Long.class,
                    DEFAULT_TIMEOUT_MINUTES);
            if (!startedProcess.waitFor(Duration.ofMinutes(timeoutMinutes).toMillis(), TimeUnit.MILLISECONDS)) {
                startedProcess.destroyForcibly();
                log.error("Python classifier timed out after {} minutes", timeoutMinutes);
            } else if (startedProcess.exitValue() == 0) {
                log.info("Python classifier completed successfully");
            } else {
                log.error("Python classifier exited with status {}", startedProcess.exitValue());
            }
            outputDrainer.join();
        } catch (IOException e) {
            log.error("Could not start Python classifier; extraction remains complete: {}", e.getMessage());
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for Python classifier");
        } finally {
            if (manifest != null) {
                try {
                    Files.deleteIfExists(manifest);
                } catch (IOException e) {
                    log.warn("Could not delete classifier manifest {}: {}", manifest, e.getMessage());
                }
            }
        }
    }

    private static void addOptionalArgument(List<String> command, Environment environment, String property, String option) {
        String value = environment.getProperty(property, "").trim();
        if (!value.isEmpty()) {
            command.add(option);
            command.add(value);
        }
    }

    private static void drainOutput(Process process) {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output.lines().forEach(line -> log.info("[classifier] {}", line));
        } catch (IOException e) {
            log.debug("Classifier output stream closed: {}", e.getMessage());
        }
    }

    private static Path resolvePath(String configured, Path baseDirectory) {
        Path path = Path.of(configured);
        return path.isAbsolute() ? path.normalize() : baseDirectory.resolve(path).toAbsolutePath().normalize();
    }
}