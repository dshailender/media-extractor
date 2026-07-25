package com.example.mediaextractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class MediaConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MediaConsolidationService.class);

    private final ConcurrentHashMap<Path, Object> targetLocks = new ConcurrentHashMap<>();

    public void consolidateMedia(Path photoSourceDir, Path videoSourceDir,
                                 Path consolidatedPhotosDir, Path consolidatedVideosDir,
                                 ExecutorService executor) throws IOException {
        log.info("Starting media consolidation phase");

        Files.createDirectories(consolidatedPhotosDir);
        Files.createDirectories(consolidatedVideosDir);
        log.info("Created consolidated directories at {} and {}", consolidatedPhotosDir, consolidatedVideosDir);

        AtomicInteger photoCount = new AtomicInteger(0);
        AtomicInteger videoCount = new AtomicInteger(0);

        try {
            List<Future<?>> futures = new ArrayList<>();
            if (Files.exists(photoSourceDir)) {
                futures.addAll(consolidateDirectory(photoSourceDir, consolidatedPhotosDir, "photo", photoCount, executor));
            }
            if (Files.exists(videoSourceDir)) {
                futures.addAll(consolidateDirectory(videoSourceDir, consolidatedVideosDir, "video", videoCount, executor));
            }

            // Wait for all submitted tasks to complete
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for consolidation tasks", ie);
                } catch (java.util.concurrent.ExecutionException ee) {
                    throw new IOException("Error in consolidation task", ee.getCause());
                }
            }
        } catch (IOException e) {
            log.error("Error during media consolidation", e);
            throw e;
        }

        log.info("Media consolidation completed: {} photos and {} videos consolidated",
                photoCount.get(), videoCount.get());
    }


    private List<Future<?>> consolidateDirectory(Path sourceDir, Path targetDir, String mediaType,
                                      AtomicInteger count, ExecutorService executor) throws IOException {
            List<Future<?>> futures = new ArrayList<>();
        List<MediaFile> mediaFiles = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                            Instant modifiedTime = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis());
                            mediaFiles.add(new MediaFile(file, modifiedTime));
                        } catch (IOException e) {
                            log.warn("Failed to read file attributes for {}: {}", file, e.getMessage());
                        }
                    });
        }

        mediaFiles.sort(Comparator.comparing(mf -> mf.modifiedTime));

        log.info("Processing {} files from {} in date-ascending order", mediaFiles.size(), mediaType + " source");

        for (MediaFile mediaFile : mediaFiles) {
            Future<?> future = executor.submit(() -> {
                try {
                    copyMediaFile(mediaFile.path, targetDir, count);
                } catch (IOException e) {
                    // wrap in unchecked to propagate via Future.get()
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }
        return futures;
    }

    private void copyMediaFile(Path sourceFile, Path targetDir, AtomicInteger count) throws IOException {
        String fileName = sourceFile.getFileName().toString();
        Path desiredTarget = targetDir.resolve(fileName).toAbsolutePath().normalize();

        Object lock = targetLocks.computeIfAbsent(desiredTarget, k -> new Object());

        synchronized (lock) {
            // Determine a unique target filename under the lock
            Path targetFile = desiredTarget;
            if (Files.exists(targetFile)) {
                targetFile = getUniqueFileName(targetDir, fileName).toAbsolutePath().normalize();
                log.debug("File collision detected. Renaming to: {}", targetFile.getFileName());
            }

            final int maxAttempts = 5;
            long backoff = 100; // ms

            int attempts = 0;
            while (attempts < maxAttempts) {
                attempts++;
                Path tempFile = null;
                try {
                    // create temp file with unique prefix in the same directory so move is atomic where supported
                    java.nio.file.Path tempFileCandidate = targetDir.resolve("tmp-" + java.util.UUID.randomUUID().toString() + ".tmp");
                    tempFile = tempFileCandidate;
                    Files.copy(sourceFile, tempFile, StandardCopyOption.COPY_ATTRIBUTES);

                    try {
                        Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException moveEx) {
                        // fallback if atomic move not supported
                        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    }

                    count.incrementAndGet();
                    log.debug("Consolidated media file: {} -> {}", sourceFile, targetFile.getFileName());
                    break; // success
                } catch (IOException e) {
                    // cleanup temp file
                    if (tempFile != null) {
                        try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                    }

                    if (attempts >= maxAttempts) {
                        log.error("Failed to copy file {} to {} after {} attempts", sourceFile, targetFile, attempts, e);
                        throw e;
                    }

                    log.warn("Attempt {}/{} failed copying {} -> {}: {}. Retrying after {}ms",
                            attempts, maxAttempts, sourceFile, targetFile, e.getMessage(), backoff);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("Interrupted during copy retry", ie); }
                    backoff *= 2;
                }
            }
        }

        // optional: remove lock to prevent memory leak (no-op if other threads are using it)
        // targetLocks.remove(desiredTarget);
    }

    private Path getUniqueFileName(Path targetDir, String fileName) {
        String baseName = fileName;
        String extension = "";

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            baseName = fileName.substring(0, lastDot);
            extension = fileName.substring(lastDot);
        }

        int counter = 1;
        Path uniquePath;
        do {
            String newFileName = baseName + "_" + counter + extension;
            uniquePath = targetDir.resolve(newFileName);
            counter++;
        } while (Files.exists(uniquePath));

        return uniquePath;
    }

    private static class MediaFile {
        Path path;
        Instant modifiedTime;

        MediaFile(Path path, Instant modifiedTime) {
            this.path = path;
            this.modifiedTime = modifiedTime;
        }
    }
}
