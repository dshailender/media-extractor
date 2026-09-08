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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

        boolean recoverStaging = environment.getProperty("media-extractor.classifier-stale-staging-recovery", Boolean.class, true);
        List<Path> recoveredStagingFiles = List.of();
        if (recoverStaging) {
            recoveredStagingFiles = triageService.recoverStaleStagingDirectoriesWithPaths(memoriesDir);
            if (!recoveredStagingFiles.isEmpty()) {
                log.info("Startup recovery restored {} orphan staged files from previous runs back to memories", recoveredStagingFiles.size());
            }
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

        // Candidate discovery across memories directory and state store
        CandidateDiscoveryResult discovery = discoverCandidates(memoriesDir, imagePaths, recoveredStagingFiles, environment, options, workingDirectory, python);

        log.info("Candidate discovery: newlyExtracted={}, filesystem={}, databaseResumed={}, stagingRecovery={}, alreadyCompleted={}, reviewPending={}, finalUniqueCandidates={}",
                discovery.newlyExtracted(), discovery.filesystemDiscovered(), discovery.databaseResumed(), discovery.stagingRecovery(),
                discovery.alreadyCompleted(), discovery.reviewPending(), discovery.candidatePaths().size());

        int totalConsidered = discovery.alreadyCompleted() + discovery.candidatePaths().size() + discovery.reviewPending();
        if (totalConsidered > 0) {
            log.info("Classification resume progress: {}% previously completed ({}/{} photos), {}% remaining ({}/{} photos) | Estimated remaining time: ~{} (based on {} CPU cores)",
                    String.format(Locale.ROOT, "%.1f", discovery.completedPercentage()), discovery.alreadyCompleted(), totalConsidered,
                    String.format(Locale.ROOT, "%.1f", discovery.remainingPercentage()), discovery.candidatePaths().size(), totalConsidered,
                    discovery.estimatedTimeFormatted(), discovery.cpuCores());
        }

        if (discovery.newlyExtracted() == 0 && !discovery.candidatePaths().isEmpty()) {
            log.info("Current extraction produced 0 images; discovered {} resumable classifier candidates under the memories directory ({}% completed, {}% remaining | ETA: ~{} on {} CPUs).",
                    discovery.candidatePaths().size(),
                    String.format(Locale.ROOT, "%.1f", discovery.completedPercentage()),
                    String.format(Locale.ROOT, "%.1f", discovery.remainingPercentage()),
                    discovery.estimatedTimeFormatted(),
                    discovery.cpuCores());
        }

        if (discovery.candidatePaths().isEmpty()) {
            if (discovery.alreadyCompleted() > 0 && discovery.alreadyCompleted() == totalConsidered) {
                log.info("All {} discovered photos under memories directory are already completed with matching fingerprints. Done.", discovery.alreadyCompleted());
            } else if (discovery.reviewPending() > 0 && discovery.reviewPending() == totalConsidered) {
                log.info("All {} remaining photos are marked review-pending and excluded from automatic reclassification.", discovery.reviewPending());
            } else {
                log.info("No new extraction output and no pending resumable classification work found in memories directory.");
            }
            return;
        }

        List<Path> finalCandidates = discovery.candidatePaths();

        if ("java-only".equals(mode)) {
            log.info("Classifier running in experimental java-only triage mode (no Python execution)");
            int maxConcurrency = environment.getProperty("media-extractor.classifier-max-concurrency", Integer.class, 256);
            JavaClassifierTriageService.TriageSummary summary = triageService.triageAndStage(finalCandidates, memoriesDir, "dry-run", maxConcurrency);
            log.info("Java-only triage complete: {} certified photos, {} deferred candidates",
                    summary.certifiedPhotos(), summary.stagedForPython());
            return;
        }

        boolean useJavaTriage = "java-triage-python".equals(mode);

        if (useJavaTriage) {
            executeWithJavaTriage(memoriesDir, finalCandidates, environment, workingDirectory, python, script, action, quarantine);
        } else {
            executeDirectPython(memoriesDir, finalCandidates, environment, workingDirectory, python, script, action, quarantine);
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
            if (triageSummary.certifiedPhotos() > 0) {
                recordCertifiedPhotos(triageSummary, python, workingDirectory, environment);
            }
            if (triageSummary.stagedForPython() == 0) {
                log.info("All {} candidate images were certified as camera photos in Java triage; bypassing Python classifier entirely!",
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
            addOptionalArgument(command, environment, "media-extractor.classifier-state-db", "--state-db");
            addOptionalArgument(command, environment, "media-extractor.classifier-fingerprint-strategy", "--fingerprint-strategy");
            addOptionalArgument(command, environment, "media-extractor.classifier-lease-timeout-seconds", "--lease-timeout");

            boolean resume = environment.getProperty("media-extractor.classifier-resume", Boolean.class, true);
            if (!resume) {
                command.add("--no-resume");
            }
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

    public record CandidateDiscoveryResult(
            int newlyExtracted,
            int filesystemDiscovered,
            int databaseResumed,
            int stagingRecovery,
            int alreadyCompleted,
            int reviewPending,
            List<Path> candidatePaths,
            double completedPercentage,
            double remainingPercentage,
            int cpuCores,
            String estimatedTimeFormatted
    ) {}

    CandidateDiscoveryResult discoverCandidates(
            Path memoriesDir,
            List<Path> imagePaths,
            List<Path> recoveredStagingFiles,
            Environment environment,
            ClassifierOptions options,
            Path workingDirectory,
            Path python) {
        Path stateScript = resolvePath(environment != null ? environment.getProperty("media-extractor.classifier-state-script", "scripts/classifier_state.py") : "scripts/classifier_state.py", workingDirectory);
        if (Files.exists(python) && Files.exists(stateScript)) {
            Path extraPathsFile = null;
            Path stagingFile = null;
            Path summaryJson = null;
            Path candidatePathsFile = null;
            try {
                extraPathsFile = Files.createTempFile("me-extra-paths-", ".txt");
                Files.write(extraPathsFile, imagePaths.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList(), StandardCharsets.UTF_8);

                stagingFile = Files.createTempFile("me-staging-recovered-", ".txt");
                Files.write(stagingFile, recoveredStagingFiles.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList(), StandardCharsets.UTF_8);

                summaryJson = Files.createTempFile("me-discovery-summary-", ".json");
                candidatePathsFile = Files.createTempFile("me-candidate-paths-", ".txt");

                List<String> command = new ArrayList<>(List.of(
                        python.toString(), stateScript.toString(), "discover",
                        "--source-dir", memoriesDir.toAbsolutePath().normalize().toString(),
                        "--extra-paths-file", extraPathsFile.toString(),
                        "--staging-recovered-file", stagingFile.toString(),
                        "--output-json", summaryJson.toString(),
                        "--output-paths-file", candidatePathsFile.toString()
                ));

                if (environment != null) {
                    addOptionalArgument(command, environment, "media-extractor.classifier-state-db", "--state-db");
                    addOptionalArgument(command, environment, "media-extractor.classifier-fingerprint-strategy", "--fingerprint-strategy");
                    boolean resume = environment.getProperty("media-extractor.classifier-resume", Boolean.class, true);
                    if (!resume) {
                        command.add("--no-resume");
                    }
                    boolean rescue = environment.getProperty("media-extractor.classifier-rescue", Boolean.class, false);
                    if (rescue) {
                        command.add("--rescue");
                    }
                }

                Process proc = new ProcessBuilder(command)
                        .directory(workingDirectory.toFile())
                        .redirectErrorStream(true)
                        .start();

                CompletableFuture<Void> drainer = CompletableFuture.runAsync(() -> drainOutput(proc));
                boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
                drainer.join();

                if (finished && proc.exitValue() == 0 && Files.exists(summaryJson) && Files.exists(candidatePathsFile)) {
                    String json = Files.readString(summaryJson, StandardCharsets.UTF_8);
                    int newlyExtracted = JavaClassifierTriageService.extractJsonIntField(json, "newly_extracted", imagePaths.size());
                    int fsDiscovered = JavaClassifierTriageService.extractJsonIntField(json, "filesystem_discovered", 0);
                    int dbResumed = JavaClassifierTriageService.extractJsonIntField(json, "database_resumed", 0);
                    int stgRecovery = JavaClassifierTriageService.extractJsonIntField(json, "staging_recovery", recoveredStagingFiles.size());
                    int alreadyCompleted = JavaClassifierTriageService.extractJsonIntField(json, "already_completed", 0);
                    int reviewPending = JavaClassifierTriageService.extractJsonIntField(json, "review_pending", 0);
                    double completedPct = JavaClassifierTriageService.extractJsonDoubleField(json, "completed_percentage", 0.0);
                    double remainingPct = JavaClassifierTriageService.extractJsonDoubleField(json, "remaining_percentage", 0.0);
                    int cpuCores = JavaClassifierTriageService.extractJsonIntField(json, "cpu_cores", Runtime.getRuntime().availableProcessors());
                    String etaFormatted = JavaClassifierTriageService.extractJsonStringField(json, "estimated_time_formatted");
                    if (etaFormatted == null || etaFormatted.isBlank()) {
                        etaFormatted = "< 10s";
                    }

                    List<Path> candidatePaths = Files.readAllLines(candidatePathsFile, StandardCharsets.UTF_8).stream()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(Path::of)
                            .toList();

                    return new CandidateDiscoveryResult(
                            newlyExtracted, fsDiscovered, dbResumed, stgRecovery,
                            alreadyCompleted, reviewPending, candidatePaths,
                            completedPct, remainingPct, cpuCores, etaFormatted
                    );
                }
            } catch (Exception e) {
                log.warn("Python candidate discovery failed ({}); falling back to filesystem scan", e.getMessage());
            } finally {
                deleteQuietly(extraPathsFile);
                deleteQuietly(stagingFile);
                deleteQuietly(summaryJson);
                deleteQuietly(candidatePathsFile);
            }
        }

        return discoverCandidatesFallback(memoriesDir, imagePaths, recoveredStagingFiles);
    }

    CandidateDiscoveryResult discoverCandidatesFallback(Path memoriesDir, List<Path> imagePaths, List<Path> recoveredStagingFiles) {
        Set<Path> candidates = new LinkedHashSet<>();
        int newlyExtracted = 0;
        for (Path p : imagePaths) {
            if (p != null && Files.isRegularFile(p)) {
                candidates.add(p.toAbsolutePath().normalize());
                newlyExtracted++;
            }
        }

        int stagingRecovery = 0;
        for (Path p : recoveredStagingFiles) {
            if (p != null && Files.isRegularFile(p)) {
                candidates.add(p.toAbsolutePath().normalize());
                stagingRecovery++;
            }
        }

        int fsDiscovered = 0;
        if (Files.isDirectory(memoriesDir)) {
            try (var stream = Files.walk(memoriesDir, 4)) {
                List<Path> fsPhotos = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String pStr = p.toString().replace('\\', '/');
                            return !pStr.contains("/.staging-") && !pStr.contains("/.classifier-state")
                                    && !pStr.contains("/quarantine/") && !pStr.contains("/videos/");
                        })
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return JavaClassifierTriageService.CAMERA_SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith)
                                    || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                                    || name.endsWith(".webp") || name.endsWith(".bmp");
                        })
                        .toList();

                for (Path p : fsPhotos) {
                    Path norm = p.toAbsolutePath().normalize();
                    if (!candidates.contains(norm)) {
                        fsDiscovered++;
                    }
                    candidates.add(norm);
                }
            } catch (IOException e) {
                log.warn("Fallback filesystem scan error in {}: {}", memoriesDir, e.getMessage());
            }
        }

        int cpuCores = Runtime.getRuntime().availableProcessors();
        double estRate = Math.max(1.0, Math.min(cpuCores * 4.5, 60.0));
        int etaSec = (int) (candidates.size() / estRate);
        String etaFormatted = formatSeconds(etaSec);

        return new CandidateDiscoveryResult(
                newlyExtracted, fsDiscovered, 0, stagingRecovery,
                0, 0, new ArrayList<>(candidates),
                0.0, candidates.isEmpty() ? 0.0 : 100.0, cpuCores, etaFormatted
        );
    }

    static String formatSeconds(int seconds) {
        if (seconds <= 0) return "< 1s";
        if (seconds < 10) return "< 10s";
        if (seconds < 60) return seconds + "s";
        int minutes = seconds / 60;
        int remSec = seconds % 60;
        if (minutes < 60) {
            return remSec > 0 ? minutes + "m " + remSec + "s" : minutes + "m";
        }
        int hours = minutes / 60;
        int remMin = minutes % 60;
        return remMin > 0 ? hours + "h " + remMin + "m" : hours + "h";
    }

    private void recordCertifiedPhotos(
            JavaClassifierTriageService.TriageSummary triageSummary,
            Path python,
            Path workingDirectory,
            Environment environment) {
        if (triageSummary == null || triageSummary.decisions() == null) {
            return;
        }
        List<Path> certified = triageSummary.decisions().stream()
                .filter(d -> d.category() == JavaClassifierTriageService.TriageCategory.PHOTO_CERTAIN)
                .map(JavaClassifierTriageService.TriageDecision::originalPath)
                .filter(p -> p != null && Files.isRegularFile(p))
                .toList();

        if (certified.isEmpty()) {
            return;
        }

        Path stateScript = resolvePath(environment != null ? environment.getProperty("media-extractor.classifier-state-script", "scripts/classifier_state.py") : "scripts/classifier_state.py", workingDirectory);
        if (!Files.exists(python) || !Files.exists(stateScript)) {
            return;
        }

        Path pathsFile = null;
        try {
            pathsFile = Files.createTempFile("me-certified-photos-", ".txt");
            Files.write(pathsFile, certified.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList(), StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>(List.of(
                    python.toString(), stateScript.toString(), "record-certified",
                    "--paths-file", pathsFile.toString()
            ));
            if (environment != null) {
                addOptionalArgument(command, environment, "media-extractor.classifier-state-db", "--state-db");
                addOptionalArgument(command, environment, "media-extractor.classifier-fingerprint-strategy", "--fingerprint-strategy");
            }

            Process proc = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            proc.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Could not record certified photos to state store: {}", e.getMessage());
        } finally {
            deleteQuietly(pathsFile);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {}
        }
    }
}