// MediaExtractorService.java
package com.example.mediaextractor;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MediaExtractorService {

    private static final Logger log = LoggerFactory.getLogger(MediaExtractorService.class);

    // Separate extensions for photos and videos
    private static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp", "raw"
    );

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mov", "avi", "mkv", "flv", "wmv", "m4v", "mpg", "mpeg", "3gp"
    );

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "tar", "gz", "tgz", "bz2", "tbz2"
    );

    private Executor executor;
    private Path tempDir;
    private final AtomicInteger queuedItems = new AtomicInteger();

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public void setTempDir(Path tempDir) {
        this.tempDir = tempDir;
    }

    public void cleanupTempDir() {
        if (tempDir != null) {
            try {
                Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.error("Error cleaning up temp directory", e);
            }
        }
    }

    public void extractMedia(Path sourceDir, Path baseMemoriesDir) {
        queuedItems.set(0);
        log.info("Starting extraction from {} into memories directory structure at {}", sourceDir, baseMemoriesDir);
        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(@NonNull Path file, BasicFileAttributes attrs) {
                    queuedItems.incrementAndGet();
                    executor.execute(() -> processFile(file, baseMemoriesDir));
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Finished scanning source directory. Queued {} items for processing", queuedItems.get());
        } catch (IOException e) {
            log.error("Error traversing source directory", e);
        }
    }

    private void processFile(Path sourceFile, Path baseMemoriesDir) {
        String fileName = sourceFile.getFileName().toString().toLowerCase();
        int year = getYearFromFile(sourceFile);
        if (isPhoto(fileName)) {
            Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("photos");
            copyMediaFileFlattened(sourceFile, targetDir, sourceFile.getFileName().toString());
        } else if (isVideo(fileName)) {
            Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("videos");
            copyMediaFileFlattened(sourceFile, targetDir, sourceFile.getFileName().toString());
        } else if (isArchiveFile(fileName)) {
            processArchive(sourceFile, baseMemoriesDir);
        }
    }

    private void copyMediaFileFlattened(Path sourceFile, Path targetDir, String fileName) {
        try {
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(fileName);
            
            // Try to copy with collision handling - use retry logic for concurrent safety
            int maxAttempts = 100;
            int attempt = 0;
            
            while (attempt < maxAttempts) {
                try {
                    // Try to copy without overwriting
                    Files.copy(sourceFile, targetFile);
                    log.info("Copied media file to: {}", targetFile);
                    return;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // File exists, try with a unique name
                    targetFile = getUniqueFileName(targetDir, fileName);
                    log.debug("File collision detected, retrying with: {}", targetFile.getFileName());
                    attempt++;
                }
            }
            
            log.error("Failed to copy file {} after {} attempts due to persistent collisions", sourceFile, maxAttempts);
        } catch (IOException e) {
            log.error("Error copying media file: {}", sourceFile, e);
        }
    }

    private void processArchive(Path archiveFile, Path baseMemoriesDir) {
        queuedItems.incrementAndGet();
        log.info("Processing archive: {} (queued items: {})", archiveFile, queuedItems.get());
        try (InputStream fis = Files.newInputStream(archiveFile);
             ArchiveInputStream ais = createArchiveInputStream(archiveFile, fis)) {

            int archiveYear = getYearFromFile(archiveFile);
            
            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                // Extract just the filename, ignoring directory structure within archive
                Path entryFileName = Path.of(entryName).getFileName();
                if (entryFileName == null) continue;
                String fileName = entryFileName.toString();

                // Try to get year from archive entry, fallback to archive year
                int year = archiveYear;
                if (entry.getLastModifiedDate() != null) {
                    year = extractYearFromArchiveEntry(entry);
                }

                if (isPhoto(fileName)) {
                    Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("photos");
                    copyArchiveEntryFlattened(ais, targetDir, fileName);
                } else if (isVideo(fileName)) {
                    Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("videos");
                    copyArchiveEntryFlattened(ais, targetDir, fileName);
                } else if (isArchiveFile(fileName)) {
                    Path tempFile = Files.createTempFile(tempDir, "nested-", ".tmp");
                    try (OutputStream tempOut = Files.newOutputStream(tempFile)) {
                        ais.transferTo(tempOut);
                    }
                    executor.execute(() -> {
                        try {
                            processArchive(tempFile, baseMemoriesDir);
                        } finally {
                            try {
                                Files.delete(tempFile);
                            } catch (IOException e) {
                                log.error("Failed to delete temp file: {}", tempFile, e);
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error("Error processing archive: {}", archiveFile, e);
        }
    }

    private void copyArchiveEntryFlattened(ArchiveInputStream ais, Path targetDir, String fileName) {
        try {
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(fileName);
            
            // Copy to temp file first, then move with collision handling
            int maxAttempts = 100;
            int attempt = 0;
            
            while (attempt < maxAttempts) {
                try {
                    // Copy to temp file first
                    Path tempFile = targetDir.resolve("temp_" + System.nanoTime() + "_" + fileName);
                    try (OutputStream out = Files.newOutputStream(tempFile)) {
                        ais.transferTo(out);
                    }
                    
                    // Try to move without overwriting
                    Files.move(tempFile, targetFile);
                    log.info("Extracted media file to: {}", targetFile);
                    return;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // Target exists, try with a unique name
                    targetFile = getUniqueFileName(targetDir, fileName);
                    log.debug("File collision detected during extract, retrying with: {}", targetFile.getFileName());
                    attempt++;
                }
            }
            
            log.error("Failed to extract file {} from archive after {} attempts due to persistent collisions", fileName, maxAttempts);
        } catch (IOException e) {
            log.error("Error extracting media file to: {}", targetDir.resolve(fileName), e);
        }
    }

    private ArchiveInputStream createArchiveInputStream(Path file, InputStream is) throws Exception {
        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".zip")) {
            return new ZipArchiveInputStream(is);
        } else if (fileName.endsWith(".tar")) {
            return new TarArchiveInputStream(is);
        } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            return new TarArchiveInputStream(new GzipCompressorInputStream(is));
        } else if (fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2")) {
            return new TarArchiveInputStream(new BZip2CompressorInputStream(is));
        } else {
            throw new UnsupportedOperationException("Unsupported archive format: " + fileName);
        }
    }

    private boolean isPhoto(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && PHOTO_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase());
    }

    private boolean isVideo(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase());
    }

    private boolean isArchiveFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return false;
        String ext = fileName.substring(dot + 1).toLowerCase();
        if (ARCHIVE_EXTENSIONS.contains(ext)) return true;
        if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tar.bz2")) return true;
        return false;
    }

    /**
     * Extracts the year from a file's last modified time.
     * Falls back to current year if metadata is unavailable.
     */
    private int getYearFromFile(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            Instant instant = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis());
            LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            return dateTime.getYear();
        } catch (IOException e) {
            log.warn("Failed to read file attributes for {}: {}. Using current year as fallback", file, e.getMessage());
            return LocalDateTime.now().getYear();
        }
    }

    /**
     * Extracts the year from an archive entry's last modified date.
     * Falls back to current year if date is unavailable.
     */
    private int extractYearFromArchiveEntry(ArchiveEntry entry) {
        try {
            if (entry.getLastModifiedDate() != null) {
                Instant instant = entry.getLastModifiedDate().toInstant();
                LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                return dateTime.getYear();
            }
        } catch (Exception e) {
            log.debug("Failed to extract year from archive entry: {}", e.getMessage());
        }
        return LocalDateTime.now().getYear();
    }

    /**
     * Generates a unique filename by appending a numeric suffix if the target file already exists.
     * For example: "photo.jpg" becomes "photo_1.jpg", "photo_2.jpg", etc.
     */
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
}