// MediaExtractorApplication.java
package com.example.mediaextractor;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class MediaExtractorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MediaExtractorApplication.class);

    private final MediaExtractorService mediaExtractorService;
    private final Environment environment;
    private final PythonClassifierProcessService classifierProcessService;

    public MediaExtractorApplication(MediaExtractorService mediaExtractorService,
                                     Environment environment,
                                     PythonClassifierProcessService classifierProcessService) {
        this.mediaExtractorService = mediaExtractorService;
        this.environment = environment;
        this.classifierProcessService = classifierProcessService;
    }

    static void main(String[] args) {
        log.info("Starting MediaExtractor application");
        SpringApplication.run(MediaExtractorApplication.class, args);
    }

    @Override
    public void run(String @NonNull ... args) throws Exception {
        if (environment.acceptsProfiles(Profiles.of("test"))) {
            log.info("Skipping media extraction run because the test profile is active");
            return;
        }

        String dateFormat = environment.getProperty("media-extractor.date-format", "yyyyMMdd_HHmmss");
        String photoPrefix = environment.getProperty("media-extractor.photo-prefix", "IMG_");
        String videoPrefix = environment.getProperty("media-extractor.video-prefix", "MOV_");
        mediaExtractorService.getMetadataService().setDateFormatPattern(dateFormat);
        mediaExtractorService.getMetadataService().setPhotoPrefix(photoPrefix);
        mediaExtractorService.getMetadataService().setVideoPrefix(videoPrefix);

        boolean sanitizeMode = false;
        Boolean classifierEnabledOverride = null;
        String classifierActionOverride = null;
        Boolean classifierQuarantineOverride = null;
        String classifierModeOverride = null;
        String sourceArgument = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String lower = arg.toLowerCase();
            if (lower.equals("--sanitize") || lower.equals("--clean")) {
                sanitizeMode = true;
            } else if (lower.startsWith("--mode=")) {
                classifierModeOverride = arg.substring("--mode=".length()).trim();
            } else if (lower.equals("--mode") && i + 1 < args.length) {
                classifierModeOverride = args[++i].trim();
            } else if (lower.equals("--classify") || lower.equals("--resume")) {
                classifierEnabledOverride = true;
            } else if (lower.equals("--no-classify")) {
                classifierEnabledOverride = false;
            } else if (lower.equals("--move") || lower.equals("--action=move")) {
                classifierEnabledOverride = true;
                classifierActionOverride = "move";
            } else if (lower.equals("--dry-run") || lower.equals("--action=dry-run")) {
                classifierEnabledOverride = true;
                classifierActionOverride = "dry-run";
            } else if (lower.equals("--copy") || lower.equals("--action=copy")) {
                classifierEnabledOverride = true;
                classifierActionOverride = "copy";
            } else if (lower.startsWith("--action=")) {
                classifierEnabledOverride = true;
                classifierActionOverride = arg.substring("--action=".length()).trim();
            } else if (lower.equals("--action") && i + 1 < args.length) {
                classifierEnabledOverride = true;
                classifierActionOverride = args[++i].trim();
            } else if (lower.equals("--quarantine") || lower.equals("--quarantine-memes")) {
                classifierQuarantineOverride = true;
            } else if (lower.equals("--no-quarantine")) {
                classifierQuarantineOverride = false;
            } else if (!arg.startsWith("--") && sourceArgument == null) {
                sourceArgument = arg;
            }
        }

        Path baseMemoriesDir = Path.of(System.getProperty("user.home")).resolve("memories").toAbsolutePath().normalize();

        if (sanitizeMode) {
            log.info("Sanitization mode requested. Sanitizing existing memories at {}", baseMemoriesDir);
            MediaExtractorService.SanitizationReport report = mediaExtractorService.sanitizeMemories(baseMemoriesDir);
            log.info("Sanitization completed: scanned={}, valid={}, corrupted={}, duplicates={}, renamed={}, cleanedDirectories={}",
                    report.scanned(), report.valid(), report.corrupted(), report.duplicates(), report.renamed(), report.cleanedDirectories());
            return;
        }

        Files.createDirectories(baseMemoriesDir);
        log.info("Base output directory ready at {}", baseMemoriesDir);

        boolean shouldExtract = sourceArgument != null || !Boolean.TRUE.equals(classifierEnabledOverride);
        Path sourceDir = null;
        if (sourceArgument != null) {
            sourceDir = Path.of(sourceArgument).toAbsolutePath().normalize();
        } else if (shouldExtract) {
            sourceDir = Path.of("C:\\Users\\Shailender\\projects\\backup").toAbsolutePath().normalize();
        }

        if (shouldExtract) {
            log.info("Starting media extraction workflow with sourceDir={}, outputBaseDir={}",
                    sourceDir, baseMemoriesDir);

            if (sourceDir == null || !Files.exists(sourceDir)) {
                log.error("Source directory does not exist: {}", sourceDir);
                System.exit(1);
            }

            int configuredThreads = environment.getProperty("media-extractor.threads", Integer.class, 0);
            int maxInFlight = environment.getProperty("media-extractor.max-in-flight", Integer.class, 256);
            long progressInterval = environment.getProperty("media-extractor.progress-interval-ms", Long.class, 5000L);
            String configuredReportDirectory = environment.getProperty("media-extractor.report-directory", "");
            Path reportDirectory = configuredReportDirectory.isBlank()
                ? baseMemoriesDir
                : Path.of(configuredReportDirectory).toAbsolutePath().normalize();

            ExecutorService configuredExecutor = configuredThreads > 0
                ? Executors.newFixedThreadPool(configuredThreads)
                : Executors.newVirtualThreadPerTaskExecutor();
            try (ExecutorService executor = configuredExecutor;
                 ProgressReporter progress = new ProgressReporter(mediaExtractorService.startReport(), progressInterval)) {
                Path tempDir = Files.createTempDirectory("media-extractor");
                log.info("Created temporary directory for archive extraction: {}", tempDir);

                boolean quarantineEnabled = environment.getProperty("media-extractor.quarantine-enabled", Boolean.class, true);
                boolean preserveTimestamps = environment.getProperty("media-extractor.preserve-timestamps", Boolean.class, true);

                mediaExtractorService.setExecutor(executor);
                mediaExtractorService.setTempDir(tempDir);
                mediaExtractorService.setMaxInFlight(maxInFlight);
                mediaExtractorService.setQuarantineEnabled(quarantineEnabled);
                mediaExtractorService.setPreserveTimestamps(preserveTimestamps);
                progress.start();
                mediaExtractorService.extractMedia(sourceDir, baseMemoriesDir);
                executor.shutdown();
                log.info("Waiting for extraction tasks to finish");
                if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                    log.error("Timed out while waiting for extraction tasks to complete");
                }
                mediaExtractorService.finishReport();
                ExtractionReport report = mediaExtractorService.getLastReport();
                if (report != null) {
                    ExtractionReportWriter.ReportPaths reportPaths = new ExtractionReportWriter()
                        .write(report, reportDirectory, sourceDir.toString(), baseMemoriesDir.toString());
                    log.info("Extraction report written to JSON={} and HTML={}", reportPaths.json(), reportPaths.html());
                    log.info("Extraction summary: scanned={}, extracted={}, duplicates={}, corrupted={}, quarantined={}, failed={}, durationMs={}, rate={}/s",
                            report.scanned(), report.extracted(), report.duplicates(), report.corrupted(), report.quarantined(), report.failed(),
                            report.durationMillis(), String.format("%.2f", report.filesPerSecond()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for tasks to complete", e);
            } finally {
                log.info("Cleaning up temporary extraction files");
                mediaExtractorService.cleanupTempDir();
            }
        } else {
            log.info("Classifier-only/resume run requested without source directory. Skipping extraction phase and proceeding directly to classification on {}",
                    baseMemoriesDir);
        }

        classifierProcessService.classifyAfterExtraction(
            baseMemoriesDir,
            mediaExtractorService.getExtractedImagePaths(),
            environment,
            new PythonClassifierProcessService.ClassifierOptions(
                classifierEnabledOverride,
                classifierActionOverride,
                classifierQuarantineOverride,
                classifierModeOverride
            ));

        log.info("Media extraction workflow completed");
    }
}