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

    private final JavaClassifierTriageService triageService;

    public PythonClassifierProcessService() {
        this(new JavaClassifierTriageService(new MediaMetadataService()));
    }

    public PythonClassifierProcessService(JavaClassifierTriageService triageService) {
        this.triageService = triageService;
    }

    public record ClassifierOptions(
        Boolean enabledOverride,
        String actionOverride,
        Boolean quarantineOverride,
        String modeOverride
    ) {
        public static ClassifierOptions defaults() {
            return new ClassifierOptions(null, null, null, null);
        }

        public ClassifierOptions(Boolean enabledOverride, String actionOverride, Boolean quarantineOverride) {
            this(enabledOverride, actionOverride, quarantineOverride, null);
        }
    }

    public void classifyAfterExtraction(Path memoriesDir, List<Path> imagePaths, Environment environment) {
        classifyAfterExtraction(memoriesDir, imagePaths, environment, ClassifierOptions.defaults());
    }

    public void classifyAfterExtraction(Path memoriesDir, List<Path> imagePaths, Environment environment, ClassifierOptions options) {
        boolean enabled = options != null && options.enabledOverride() != null
                ? options.enabledOverride()
                : environment.getProperty("media-extractor.classifier-enabled", Boolean.class, true);
        if (!enabled) {
            return;
        }

        String mode = options != null && options.modeOverride() != null
                ? options.modeOverride().toLowerCase()
                : environment.getProperty("media-extractor.classifier-mode", "java-triage-python").toLowerCase();

        if ("disabled".equals(mode) || "false".equals(mode) || "off".equals(mode)) {
            log.info("Media Extractor classifier is disabled (mode={})", mode);
            return;
        }

        if (imagePaths.isEmpty()) {
            log.info("Media Extractor classifier enabled, but no images were extracted in this run");
            return;
        }

        if ("java-only".equals(mode)) {
            log.info("Classifier running in experimental java-only triage mode (no Python execution)");
            int maxConcurrency = environment.getProperty("media-extractor.classifier-max-concurrency", Integer.class, 256);
            JavaClassifierTriageService.TriageSummary summary = triageService.triageAndStage(imagePaths, memoriesDir, "dry-run", maxConcurrency);
            log.info("Java-only triage complete: {} certified photos, {} deferred candidates",
                    summary.certifiedPhotos(), summary.stagedForPython());
            return;
        }

        Path workingDirectory = resolvePath(environment.getProperty("media-extractor.classifier-working-directory", ""), Path.of("."));
        Path python = resolvePath(environment.getProperty("media-extractor.classifier-python", ".venv/bin/python"), workingDirectory);
        Path script = resolvePath(environment.getProperty("media-extractor.classifier-script", "scripts/classify_memes.py"), workingDirectory);
        String action = options != null && options.actionOverride() != null
                ? options.actionOverride()
                : environment.getProperty("media-extractor.classifier-action", "move");
        if (!List.of("dry-run", "copy", "move").contains(action)) {
            log.error("Invalid classifier action '{}'; expected dry-run, copy, or move", action);
            return;
        }

        boolean quarantine = options != null && options.quarantineOverride() != null
                ? options.quarantineOverride()
                : environment.getProperty("media-extractor.classifier-quarantine", Boolean.class, true);

        boolean useJavaTriage = "java-triage-python".equals(mode);

        if (useJavaTriage) {
            executeWithJavaTriage(memoriesDir, imagePaths, environment, workingDirectory, python, script, action, quarantine);
        } else {
            executeDirectPython(memoriesDir, imagePaths, environment, workingDirectory, python, script, action, quarantine);
        }
    }

    private void executeWithJavaTriage(
            Path memoriesDir,
            List<Path> imagePaths,
            Environment environment,
            Path workingDirectory,
            Path python,
            Path script,
            String action,
            boolean quarantine) {
        int maxConcurrency = environment.getProperty("media-extractor.classifier-max-concurrency", Integer.class, 256);
        JavaClassifierTriageService.TriageSummary triageSummary = null;
        Process process = null;
        try {
            triageSummary = triageService.triageAndStage(imagePaths, memoriesDir, action, maxConcurrency);
            if (triageSummary.stagedForPython() == 0) {
                log.info("All {} extracted images were certified as camera photos in Java triage; bypassing Python classifier entirely!",
                        imagePaths.size());
                return;
            }

            log.info("Java triage staged {} candidates for Python evaluation ({} certified photos retained in memories)",
                    triageSummary.stagedForPython(), triageSummary.certifiedPhotos());

            Path manifest = triageSummary.manifestPath();
            List<String> command = buildCommand(python, script, memoriesDir, manifest, action, quarantine, environment);

            log.info("Starting Python classifier for {} staged images using {}", triageSummary.stagedForPython(), script);
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
            log.error("Could not run classification workflow: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for Python classifier");
        } finally {
            if (triageSummary != null) {
                int restored = triageService.restoreStrandedFiles(triageSummary);
                if (restored > 0) {
                    log.warn("Safety restored {} unclassified files from staging back to memories photos directory", restored);
                }
                triageService.cleanupStagingDir(triageSummary);
            }
        }
    }

    private void executeDirectPython(
            Path memoriesDir,
            List<Path> imagePaths,
            Environment environment,
            Path workingDirectory,
            Path python,
            Path script,
            String action,
            boolean quarantine) {
        Path manifest = null;
        Process process = null;
        try {
            manifest = Files.createTempFile("media-extractor-classifier-", ".manifest");
            Files.write(manifest, imagePaths.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList(),
                    StandardCharsets.UTF_8);

            List<String> command = buildCommand(python, script, memoriesDir, manifest, action, quarantine, environment);

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

    static List<String> buildCommand(
            Path python,
            Path script,
            Path memoriesDir,
            Path manifest,
            String action,
            boolean quarantine,
            Environment environment) {
        List<String> command = new ArrayList<>(List.of(
                python.toString(), script.toString(),
                "--source-dir", memoriesDir.toAbsolutePath().normalize().toString(),
                "--input-manifest", manifest.toString(),
                "--action", action));
        if (quarantine) {
            command.add("--quarantine");
        }
        if (environment != null) {
            addOptionalArgument(command, environment, "media-extractor.classifier-output-csv", "--output-csv");
            addOptionalArgument(command, environment, "media-extractor.classifier-review-csv", "--review-csv");
        }
        return command;
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