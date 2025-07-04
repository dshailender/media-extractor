// MediaExtractorApplication.java
package com.example.mediaextractor;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class MediaExtractorApplication implements CommandLineRunner {

    private final MediaExtractorService mediaExtractorService;

    public MediaExtractorApplication(MediaExtractorService mediaExtractorService) {
        this.mediaExtractorService = mediaExtractorService;
    }

    public static void main(String[] args) {
        SpringApplication.run(MediaExtractorApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java -jar <jarfile> <sourceDir> <photoTargetDir> <videoTargetDir>");
            System.exit(1);
        }

        Path sourceDir = Path.of(args[0]);
        Path photoTargetDir = Path.of(args[1]);
        Path videoTargetDir = Path.of(args[2]);

        if (!Files.exists(sourceDir)) {
            System.err.println("Source directory does not exist: " + sourceDir);
            System.exit(1);
        }

        Files.createDirectories(photoTargetDir);
        Files.createDirectories(videoTargetDir);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            mediaExtractorService.setExecutor(executor);
            mediaExtractorService.setTempDir(Files.createTempDirectory("media-extractor"));
            mediaExtractorService.extractMedia(sourceDir, photoTargetDir, videoTargetDir);
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                System.err.println("Timeout waiting for tasks to complete");
            }
        } finally {
            mediaExtractorService.cleanupTempDir();
        }
    }
}