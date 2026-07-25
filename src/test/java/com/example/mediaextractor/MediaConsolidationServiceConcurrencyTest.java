package com.example.mediaextractor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaConsolidationServiceConcurrencyTest {

    private Path tempRoot;

    @AfterEach
    void cleanup() throws IOException {
        if (tempRoot != null && Files.exists(tempRoot)) {
            Files.walk(tempRoot)
                    .sorted((a, b) -> b.compareTo(a)) // delete children first
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    @Test
    void concurrentConsolidationCreatesUniqueFiles() throws Exception {
        tempRoot = Files.createTempDirectory("mcstest-");
        Path photoSource = tempRoot.resolve("photos-src");
        Path videoSource = tempRoot.resolve("videos-src");
        Files.createDirectories(photoSource);
        Files.createDirectories(videoSource);

        // Create many files with the same filename in different subdirectories to cause collisions
        int fileCount = 100;
        String baseName = "same_name.jpg";

        IntStream.range(0, fileCount).forEach(i -> {
            try {
                Path sub = photoSource.resolve("sub" + i);
                Files.createDirectories(sub);
                Path f = sub.resolve(baseName);
                Files.write(f, ("photo " + i).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Path consolidatedPhotos = tempRoot.resolve("consolidated").resolve("photos");
        Path consolidatedVideos = tempRoot.resolve("consolidated").resolve("videos");
        Files.createDirectories(consolidatedPhotos);
        Files.createDirectories(consolidatedVideos);

        MediaConsolidationService svc = new MediaConsolidationService();

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            svc.consolidateMedia(photoSource, videoSource, consolidatedPhotos, consolidatedVideos, exec);
            exec.shutdown();
            if (!exec.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Consolidation executor timed out");
            }
        }

        // Verify consolidated directory contains fileCount files with unique names
        try (var stream = Files.list(consolidatedPhotos)) {
            Set<String> names = new HashSet<>();
            stream.forEach(p -> names.add(p.getFileName().toString()));
            assertEquals(fileCount, names.size(), "Expected consolidated photos count to match source count");
        }

        // Basic assertion: files exist
        assertTrue(Files.list(consolidatedPhotos).findAny().isPresent());
    }
}
