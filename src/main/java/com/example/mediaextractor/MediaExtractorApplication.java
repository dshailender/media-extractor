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
    private final MediaConsolidationService consolidationService;
    private final Environment environment;

    public MediaExtractorApplication(MediaExtractorService mediaExtractorService,
                                     MediaConsolidationService consolidationService,
                                     Environment environment) {
        this.mediaExtractorService = mediaExtractorService;
        this.consolidationService = consolidationService;
        this.environment = environment;
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

        boolean consolidate = Arrays.asList(args).contains("--consolidate");
        String sourceArgument = Arrays.stream(args)
                .filter(arg -> !arg.equals("--consolidate"))
                .findFirst()
                .orElse(null);

        Path sourceDir = sourceArgument != null
                ? Path.of(sourceArgument).toAbsolutePath().normalize()
                : Path.of("C:\\Users\\Shailender\\projects\\backup").toAbsolutePath().normalize();
        Path projectTargetDir = sourceDir.getParent() != null
                ? sourceDir.getParent().resolve("target")
                : Path.of("target");
        Path photoTargetDir = projectTargetDir.resolve("photos");
        Path videoTargetDir = projectTargetDir.resolve("videos");
        Path consolidatedPhotoDir = projectTargetDir.resolve("consolidated").resolve("photos");
        Path consolidatedVideoDir = projectTargetDir.resolve("consolidated").resolve("videos");

        log.info("Starting media extraction workflow with sourceDir={}, consolidate={}, photoTargetDir={}, videoTargetDir={}",
                sourceDir, consolidate, photoTargetDir, videoTargetDir);

        if (!Files.exists(sourceDir)) {
            log.error("Source directory does not exist: {}", sourceDir);
            System.exit(1);
        }

        Files.createDirectories(photoTargetDir);
        Files.createDirectories(videoTargetDir);
        log.info("Output directories ready for photos and videos at {} and {}", photoTargetDir, videoTargetDir);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Path tempDir = Files.createTempDirectory("media-extractor");
            log.info("Created temporary directory for archive extraction: {}", tempDir);

            mediaExtractorService.setExecutor(executor);
            mediaExtractorService.setTempDir(tempDir);
            mediaExtractorService.extractMedia(sourceDir, photoTargetDir, videoTargetDir);
            executor.shutdown();
            log.info("Waiting for extraction tasks to finish");
            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                log.error("Timed out while waiting for extraction tasks to complete");
            }

            if (consolidate) {
                log.info("Consolidation flag enabled. Starting consolidation phase");
                try (ExecutorService consolidationExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                    consolidationService.consolidateMedia(photoTargetDir, videoTargetDir,
                            consolidatedPhotoDir, consolidatedVideoDir, consolidationExecutor);
                    consolidationExecutor.shutdown();
                    log.info("Waiting for consolidation tasks to finish");
                    if (!consolidationExecutor.awaitTermination(1, TimeUnit.DAYS)) {
                        log.error("Timed out while waiting for consolidation tasks to complete");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for tasks to complete", e);
        } finally {
            log.info("Cleaning up temporary extraction files");
            mediaExtractorService.cleanupTempDir();
        }

        log.info("Media extraction workflow completed");
    }
}