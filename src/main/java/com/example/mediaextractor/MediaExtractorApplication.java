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

    public MediaExtractorApplication(MediaExtractorService mediaExtractorService,
                                     Environment environment) {
        this.mediaExtractorService = mediaExtractorService;
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

        String sourceArgument = Arrays.stream(args)
                .findFirst()
                .orElse(null);

        Path sourceDir = sourceArgument != null
                ? Path.of(sourceArgument).toAbsolutePath().normalize()
                : Path.of("C:\\Users\\Shailender\\projects\\backup").toAbsolutePath().normalize();
        
        Path baseMemoriesDir = Path.of(System.getProperty("user.home")).resolve("memories").toAbsolutePath().normalize();

        log.info("Starting media extraction workflow with sourceDir={}, outputBaseDir={}",
                sourceDir, baseMemoriesDir);

        if (!Files.exists(sourceDir)) {
            log.error("Source directory does not exist: {}", sourceDir);
            System.exit(1);
        }

        Files.createDirectories(baseMemoriesDir);
        log.info("Base output directory ready at {}", baseMemoriesDir);

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

            mediaExtractorService.setExecutor(executor);
            mediaExtractorService.setTempDir(tempDir);
            mediaExtractorService.setMaxInFlight(maxInFlight);
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
                log.info("Extraction summary: scanned={}, extracted={}, duplicates={}, corrupted={}, failed={}, durationMs={}, rate={}/s",
                        report.scanned(), report.extracted(), report.duplicates(), report.corrupted(), report.failed(),
                        report.durationMillis(), String.format("%.2f", report.filesPerSecond()));
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