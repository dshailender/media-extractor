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
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

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
    private final Set<String> seenHashes = ConcurrentHashMap.newKeySet();
    private volatile Semaphore inFlightLimit = new Semaphore(Integer.MAX_VALUE);
    private volatile ExtractionReport lastReport;

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public void setTempDir(Path tempDir) {
        this.tempDir = tempDir;
    }

    public void setMaxInFlight(int maxInFlight) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        inFlightLimit = new Semaphore(maxInFlight);
    }

    public ExtractionReport getLastReport() {
        return lastReport;
    }

    public ExtractionReport startReport() {
        seenHashes.clear();
        lastReport = new ExtractionReport();
        return lastReport;
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
        ExtractionReport report = lastReport;
        if (report == null || report.finishedAt() != null) {
            report = startReport();
        }
        final ExtractionReport runReport = report;
        log.info("Starting extraction from {} into memories directory structure at {}", sourceDir, baseMemoriesDir);
        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(@NonNull Path file, BasicFileAttributes attrs) {
                    queuedItems.incrementAndGet();
                    runReport.recordScanned();
                    runReport.recordQueued();
                    inFlightLimit.acquireUninterruptibly();
                    try {
                        executor.execute(() -> {
                            runReport.started();
                            try {
                                processFile(file, baseMemoriesDir, runReport);
                            } finally {
                                runReport.recordCompleted();
                                inFlightLimit.release();
                            }
                        });
                    } catch (RuntimeException e) {
                        inFlightLimit.release();
                        runReport.failed(file.toString(), "queue", e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Finished scanning source directory. Queued {} items for processing", queuedItems.get());
        } catch (IOException e) {
            log.error("Error traversing source directory", e);
            runReport.failed(sourceDir.toString(), "scan", e.getMessage());
        }
    }

    public void finishReport() {
        if (lastReport != null) {
            lastReport.finish();
        }
    }

    private void processFile(Path sourceFile, Path baseMemoriesDir, ExtractionReport report) {
        String fileName = sourceFile.getFileName().toString().toLowerCase();
        if (isCorrupted(sourceFile, fileName)) {
            log.warn("Skipping corrupted file: {}", sourceFile);
            report.corrupted(sourceFile.toString(), "file failed validation or is empty");
            return;
        }

        int year = getYearFromFile(sourceFile);
        if (isPhoto(fileName)) {
            Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("photos");
            copyMediaFileFlattened(sourceFile, targetDir, sourceFile.getFileName().toString(), "photo", year, report);
        } else if (isVideo(fileName)) {
            Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("videos");
            copyMediaFileFlattened(sourceFile, targetDir, sourceFile.getFileName().toString(), "video", year, report);
        } else if (isArchiveFile(fileName)) {
            processArchive(sourceFile, baseMemoriesDir, report);
        }
    }

    private void copyMediaFileFlattened(Path sourceFile, Path targetDir, String fileName, String type, int year, ExtractionReport report) {
        try {
            Files.createDirectories(targetDir);
            String digest = computeFileDigest(sourceFile);
            if (digest != null && !seenHashes.add(digest)) {
                log.info("Skipping duplicate media content for {} (already extracted)", sourceFile);
                report.duplicate(sourceFile.toString());
                return;
            }

            Path targetFile = targetDir.resolve(fileName);
            
            // Try to copy with collision handling - use retry logic for concurrent safety
            int maxAttempts = 100;
            int attempt = 0;
            
            while (attempt < maxAttempts) {
                try {
                    // Try to copy without overwriting
                    Files.copy(sourceFile, targetFile);
                    log.info("Copied media file to: {}", targetFile);
                    report.extracted(type, year, Files.size(sourceFile));
                    return;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // File exists, try with a unique name
                    targetFile = getUniqueFileName(targetDir, fileName);
                    log.debug("File collision detected, retrying with: {}", targetFile.getFileName());
                    attempt++;
                }
            }
            
            log.error("Failed to copy file {} after {} attempts due to persistent collisions", sourceFile, maxAttempts);
            report.failed(sourceFile.toString(), "copy", "persistent filename collisions");
        } catch (IOException e) {
            log.error("Error copying media file: {}", sourceFile, e);
            report.failed(sourceFile.toString(), "copy", e.getMessage());
        }
    }

    private boolean isCorrupted(Path file, String fileName) {
        try {
            if (!Files.exists(file) || Files.size(file) <= 0) {
                return true;
            }

            if (isPhoto(fileName)) {
                byte[] bytes = Files.readAllBytes(file);
                if (looksLikeJpeg(bytes) || looksLikePng(bytes) || looksLikeGif(bytes) || looksLikeBmp(bytes) || looksLikeTiff(bytes)) {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                    return image == null;
                }
                return false;
            }

            if (isArchiveFile(fileName)) {
                try (InputStream in = Files.newInputStream(file);
                     ArchiveInputStream<?> ais = createArchiveInputStream(file, in)) {
                    return ais.getNextEntry() == null;
                } catch (Exception e) {
                    return true;
                }
            }

            return false;
        } catch (IOException e) {
            log.warn("File {} could not be read for corruption check: {}", file, e.getMessage());
            return true;
        }
    }

    private boolean looksLikeJpeg(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8;
    }

    private boolean looksLikePng(byte[] bytes) {
        return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
    }

    private boolean looksLikeGif(byte[] bytes) {
        return bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
    }

    private boolean looksLikeBmp(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M';
    }

    private boolean looksLikeTiff(byte[] bytes) {
        return bytes.length >= 2 && ((bytes[0] == 'I' && bytes[1] == 'I') || (bytes[0] == 'M' && bytes[1] == 'M'));
    }

    private String computeFileDigest(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            log.debug("Unable to compute duplicate hash for {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void processArchive(Path archiveFile, Path baseMemoriesDir, ExtractionReport report) {
        queuedItems.incrementAndGet();
        log.info("Processing archive: {} (queued items: {})", archiveFile, queuedItems.get());
        try (InputStream fis = Files.newInputStream(archiveFile);
             ArchiveInputStream<?> ais = createArchiveInputStream(archiveFile, fis)) {

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
                    copyArchiveEntryFlattened(ais, targetDir, fileName, "photo", year, report);
                } else if (isVideo(fileName)) {
                    Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("videos");
                    copyArchiveEntryFlattened(ais, targetDir, fileName, "video", year, report);
                } else if (isArchiveFile(fileName)) {
                    Path tempFile = Files.createTempFile(tempDir, "nested-", getArchiveFileSuffix(fileName));
                    try (OutputStream tempOut = Files.newOutputStream(tempFile)) {
                        ais.transferTo(tempOut);
                    }
                    try {
                        processArchive(tempFile, baseMemoriesDir, report);
                    } finally {
                        try {
                            Files.delete(tempFile);
                        } catch (IOException e) {
                            log.error("Failed to delete temp file: {}", tempFile, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing archive: {}", archiveFile, e);
            report.failed(archiveFile.toString(), "archive", e.getMessage());
        }
    }

    private void copyArchiveEntryFlattened(ArchiveInputStream<?> ais, Path targetDir, String fileName, String type, int year, ExtractionReport report) {
        Path tempFile = null;
        try {
            Files.createDirectories(targetDir);
            tempFile = Files.createTempFile(targetDir, ".media-entry-", ".tmp");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                ais.transferTo(out);
            }

            String digest = computeFileDigest(tempFile);
            if (digest != null && !seenHashes.add(digest)) {
                log.info("Skipping duplicate archived media content for {}", fileName);
                report.duplicate(fileName);
                return;
            }

            Path targetFile = targetDir.resolve(fileName);
            
            // Copy to temp file first, then move with collision handling
            int maxAttempts = 100;
            int attempt = 0;
            
            while (attempt < maxAttempts) {
                try {
                    // Try to move without overwriting
                    Files.move(tempFile, targetFile);
                    log.info("Extracted media file to: {}", targetFile);
                    report.extracted(type, year, Files.size(targetFile));
                    tempFile = null;
                    return;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    // Target exists, try with a unique name
                    targetFile = getUniqueFileName(targetDir, fileName);
                    log.debug("File collision detected during extract, retrying with: {}", targetFile.getFileName());
                    attempt++;
                }
            }
            
            log.error("Failed to extract file {} from archive after {} attempts due to persistent collisions", fileName, maxAttempts);
            report.failed(fileName, "copy", "persistent filename collisions");
        } catch (IOException e) {
            log.error("Error extracting media file to: {}", targetDir.resolve(fileName), e);
            report.failed(fileName, "copy", e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temporary archive entry {}: {}", tempFile, e.getMessage());
                }
            }
        }
    }

    private ArchiveInputStream<?> createArchiveInputStream(Path file, InputStream is) throws Exception {
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

    private String getArchiveFileSuffix(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".tar.gz")) return ".tar.gz";
        if (lowerName.endsWith(".tar.bz2")) return ".tar.bz2";
        if (lowerName.endsWith(".tgz")) return ".tgz";
        if (lowerName.endsWith(".tbz2")) return ".tbz2";

        int lastDot = lowerName.lastIndexOf('.');
        if (lastDot > 0) {
            return lowerName.substring(lastDot);
        }

        return ".tmp";
    }

    /**
     * Extracts the year from a file's last modified time.
     * Falls back to current year if metadata is unavailable.
     */
    private int getYearFromFile(Path file) {
        Integer exifYear = extractExifYear(file);
        if (exifYear != null) {
            return exifYear;
        }

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

    private Integer extractExifYear(Path file) {
        if (!isPhoto(file.getFileName().toString())) {
            return null;
        }

        try {
            byte[] bytes = Files.readAllBytes(file);
            Integer year = extractYearFromExif(bytes);
            if (year != null) {
                return year;
            }
        } catch (IOException e) {
            log.debug("Unable to read EXIF metadata for {}: {}", file, e.getMessage());
        }
        return null;
    }

    private Integer extractYearFromExif(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < 10) {
            return null;
        }

        if (imageBytes[0] != (byte) 0xFF || imageBytes[1] != (byte) 0xD8) {
            return null;
        }

        int offset = 2;
        while (offset + 1 < imageBytes.length) {
            if (imageBytes[offset] != (byte) 0xFF) {
                break;
            }

            int marker = imageBytes[offset + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) {
                break;
            }

            int segmentLength = ((imageBytes[offset + 2] & 0xFF) << 8) | (imageBytes[offset + 3] & 0xFF);
            int payloadStart = offset + 4;
            int payloadEnd = payloadStart + segmentLength - 2;
            if (payloadEnd > imageBytes.length) {
                break;
            }

            if (marker == 0xE1 && payloadStart + 6 <= payloadEnd) {
                byte[] segment = Arrays.copyOfRange(imageBytes, payloadStart, payloadEnd);
                if (segment.length >= 6 && segment[0] == 0x45 && segment[1] == 0x78 && segment[2] == 0x69 && segment[3] == 0x66 && segment[4] == 0x00 && segment[5] == 0x00) {
                    Integer year = parseExifYearFromTiff(segment, 6);
                    if (year != null) {
                        return year;
                    }
                }
            }

            offset = payloadEnd;
        }

        return null;
    }

    private Integer parseExifYearFromTiff(byte[] exifSegment, int tiffStart) {
        if (tiffStart + 8 > exifSegment.length) {
            return null;
        }

        boolean littleEndian = exifSegment[tiffStart] == 0x49 && exifSegment[tiffStart + 1] == 0x49;
        boolean bigEndian = exifSegment[tiffStart] == 0x4D && exifSegment[tiffStart + 1] == 0x4D;
        if (!littleEndian && !bigEndian) {
            return null;
        }

        int magicNumber = readUnsignedShort(exifSegment, tiffStart + 2, littleEndian);
        if (magicNumber != 42) {
            return null;
        }

        int ifd0Offset = readUnsignedInt(exifSegment, tiffStart + 4, littleEndian);
        return extractYearFromIfd(exifSegment, tiffStart + ifd0Offset, littleEndian, tiffStart);
    }

    private Integer extractYearFromIfd(byte[] exifSegment, int ifdOffset, boolean littleEndian, int tiffStart) {
        if (ifdOffset < 0 || ifdOffset + 2 > exifSegment.length) {
            return null;
        }

        int entryCount = readUnsignedShort(exifSegment, ifdOffset, littleEndian);
        int cursor = ifdOffset + 2;
        for (int i = 0; i < entryCount; i++) {
            if (cursor + 12 > exifSegment.length) {
                return null;
            }

            int tag = readUnsignedShort(exifSegment, cursor, littleEndian);
            int type = readUnsignedShort(exifSegment, cursor + 2, littleEndian);
            int count = readUnsignedInt(exifSegment, cursor + 4, littleEndian);
            int valueOrOffset = readUnsignedInt(exifSegment, cursor + 8, littleEndian);

            if (tag == 0x8769 && valueOrOffset != 0) {
                Integer nestedYear = extractYearFromIfd(exifSegment, tiffStart + valueOrOffset, littleEndian, tiffStart);
                if (nestedYear != null) {
                    return nestedYear;
                }
            }

            if ((tag == 0x9003 || tag == 0x9004 || tag == 0x0132) && type == 2 && valueOrOffset != 0 && count > 0) {
                String value = readAsciiValue(exifSegment, tiffStart + valueOrOffset, count);
                if (value != null && value.length() >= 4) {
                    try {
                        int year = Integer.parseInt(value.substring(0, 4));
                        if (year >= 1900 && year <= 2100) {
                            return year;
                        }
                    } catch (NumberFormatException ignored) {
                        // continue to the next candidate
                    }
                }
            }

            cursor += 12;
        }

        return null;
    }

    private String readAsciiValue(byte[] exifSegment, int valueOffset, int count) {
        if (valueOffset < 0 || valueOffset + count > exifSegment.length) {
            return null;
        }
        byte[] valueBytes = Arrays.copyOfRange(exifSegment, valueOffset, valueOffset + count);
        int end = 0;
        while (end < valueBytes.length && valueBytes[end] != 0) {
            end++;
        }
        return new String(valueBytes, 0, end, StandardCharsets.US_ASCII);
    }

    private int readUnsignedShort(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset + 2 > data.length) {
            return 0;
        }
        if (littleEndian) {
            return ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
        }
        return (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private int readUnsignedInt(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset + 4 > data.length) {
            return 0;
        }
        if (littleEndian) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        }
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
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