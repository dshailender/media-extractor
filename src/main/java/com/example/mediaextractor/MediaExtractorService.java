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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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

    public void extractMedia(Path sourceDir, Path photoTargetDir, Path videoTargetDir) {
        queuedItems.set(0);
        log.info("Starting extraction from {} into photo target {} and video target {}", sourceDir, photoTargetDir, videoTargetDir);
        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(@NonNull Path file, BasicFileAttributes attrs) {
                    Path relativePath = sourceDir.relativize(file);
                    queuedItems.incrementAndGet();
                    executor.execute(() -> processFile(file, relativePath, photoTargetDir, videoTargetDir));
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Finished scanning source directory. Queued {} items for processing", queuedItems.get());
        } catch (IOException e) {
            log.error("Error traversing source directory", e);
        }
    }

    private void processFile(Path sourceFile, Path relativePath, Path photoTargetDir, Path videoTargetDir) {
        String fileName = sourceFile.getFileName().toString().toLowerCase();
        if (isPhoto(fileName)) {
            copyMediaFile(sourceFile, photoTargetDir.resolve(relativePath));
        } else if (isVideo(fileName)) {
            copyMediaFile(sourceFile, videoTargetDir.resolve(relativePath));
        } else if (isArchiveFile(fileName)) {
            processArchive(sourceFile, relativePath, photoTargetDir, videoTargetDir);
        }
    }

    private void copyMediaFile(Path sourceFile, Path targetFile) {
        try {
            Files.createDirectories(targetFile.getParent());
            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Copied media file to: {}", targetFile);
        } catch (IOException e) {
            log.error("Error copying media file: {}", sourceFile, e);
        }
    }

    private void processArchive(Path archiveFile, Path relativePath, Path photoTargetDir, Path videoTargetDir) {
        queuedItems.incrementAndGet();
        log.info("Processing archive: {} (queued items: {})", archiveFile, queuedItems.get());
        try (InputStream fis = Files.newInputStream(archiveFile);
             ArchiveInputStream ais = createArchiveInputStream(archiveFile, fis)) {

            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                Path normalizedEntryName = Path.of(entryName).normalize();
                Path entryRelativePath = relativePath.resolveSibling(
                        relativePath.getFileName() + "_extracted").resolve(normalizedEntryName);

                if (isPhoto(entryName)) {
                    Path targetFile = photoTargetDir.resolve(entryRelativePath);
                    copyArchiveEntry(ais, targetFile);
                } else if (isVideo(entryName)) {
                    Path targetFile = videoTargetDir.resolve(entryRelativePath);
                    copyArchiveEntry(ais, targetFile);
                } else if (isArchiveFile(entryName)) {
                    Path tempFile = Files.createTempFile(tempDir, "nested-", ".tmp");
                    try (OutputStream tempOut = Files.newOutputStream(tempFile)) {
                        ais.transferTo(tempOut);
                    }
                    executor.execute(() -> {
                        try {
                            processArchive(tempFile, entryRelativePath, photoTargetDir, videoTargetDir);
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

    private void copyArchiveEntry(ArchiveInputStream ais, Path targetFile) throws IOException {
        try {
            Files.createDirectories(targetFile.getParent());
            try (OutputStream out = Files.newOutputStream(targetFile)) {
                ais.transferTo(out);
            }
            log.info("Extracted media file to: {}", targetFile);
        } catch (IOException e) {
            log.error("Error extracting media file to: {}", targetFile, e);
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
}