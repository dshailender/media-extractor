// MediaExtractorService.java
package com.example.mediaextractor;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MediaExtractorService {

    private static final Logger log = LoggerFactory.getLogger(MediaExtractorService.class);

    private static final Set<String> IGNORED_SYSTEM_DIRECTORIES = Set.of(
            "$recycle.bin",
            "system volume information",
            "recovery",
            "config.msi",
            "msocache",
            "$winreagent",
            "$sysreset",
            ".trash",
            ".trash-1000",
            ".trashes",
            ".fseventsd",
            ".spotlight-v100",
            ".temporaryitems"
    );

    public static boolean isSystemOrRecycleDirectory(Path dir) {
        if (dir == null || dir.getFileName() == null) {
            return false;
        }
        String name = dir.getFileName().toString().trim();
        if (name.isEmpty()) {
            return false;
        }
        if (name.startsWith("$")) {
            return true;
        }
        return IGNORED_SYSTEM_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT));
    }

    private final MediaMetadataService metadataService;
    private final MediaIntegrityService integrityService;
    private final DuplicateDetectionService duplicateService;

    private Executor executor;
    private Path tempDir;
    private final AtomicInteger queuedItems = new AtomicInteger();
    private volatile Semaphore inFlightLimit = new Semaphore(Integer.MAX_VALUE);
    private volatile ExtractionReport lastReport;
    private boolean quarantineEnabled = true;
    private boolean preserveTimestamps = true;
    private final CopyOnWriteArrayList<Path> extractedImagePaths = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Path> newlyExtractedFiles = new CopyOnWriteArrayList<>();

    public MediaExtractorService() {
        this(new MediaMetadataService());
    }

    public MediaExtractorService(MediaMetadataService metadataService) {
        this(metadataService, new MediaIntegrityService(metadataService), new DuplicateDetectionService());
    }

    public MediaExtractorService(MediaMetadataService metadataService,
                                 MediaIntegrityService integrityService,
                                 DuplicateDetectionService duplicateService) {
        this.metadataService = metadataService;
        this.integrityService = integrityService;
        this.duplicateService = duplicateService;
    }

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

    public void setQuarantineEnabled(boolean quarantineEnabled) {
        this.quarantineEnabled = quarantineEnabled;
    }

    public void setPreserveTimestamps(boolean preserveTimestamps) {
        this.preserveTimestamps = preserveTimestamps;
    }

    public MediaMetadataService getMetadataService() {
        return metadataService;
    }

    public ExtractionReport getLastReport() {
        return lastReport;
    }

    public ExtractionReport startReport() {
        duplicateService.clear();
        extractedImagePaths.clear();
        newlyExtractedFiles.clear();
        lastReport = new ExtractionReport();
        return lastReport;
    }

    public List<Path> getExtractedImagePaths() {
        return List.copyOf(extractedImagePaths);
    }

    public List<Path> getNewlyExtractedFiles() {
        return List.copyOf(newlyExtractedFiles);
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

        // Pre-index existing memories directory so subsequent runs are idempotent
        duplicateService.indexExistingDirectory(baseMemoriesDir);

        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isSystemOrRecycleDirectory(dir)) {
                        log.debug("Skipping system/recycle directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NonNull Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    // Early filter: skip non-media files immediately without acquiring permits
                    if (!metadataService.isSupportedMedia(fileName)) {
                        return FileVisitResult.CONTINUE;
                    }

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

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Skipping inaccessible path during scan: {} ({})", file, exc.getMessage());
                    runReport.failed(file.toString(), "scan_access", exc.getClass().getSimpleName() + ": " + exc.getMessage());
                    return FileVisitResult.SKIP_SUBTREE;
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
        String fileName = sourceFile.getFileName().toString();

        if (isCorrupted(sourceFile, fileName)) {
            log.warn("Skipping corrupted file: {}", sourceFile);
            report.corrupted(sourceFile.toString(), "file failed validation or is empty");

            if (quarantineEnabled) {
                MediaMetadataService.Metadata meta = metadataService.extractMetadata(sourceFile);
                String type = isPhoto(fileName) ? "photo" : (isVideo(fileName) ? "video" : "archive");
                Path quarantined = integrityService.quarantineFile(sourceFile, baseMemoriesDir, meta.year(), type, "file failed validation or is empty");
                if (quarantined != null) {
                    newlyExtractedFiles.add(quarantined.toAbsolutePath().normalize());
                    report.quarantined(sourceFile.toString(), "quarantined to " + quarantined);
                }
            }
            return;
        }

        MediaMetadataService.Metadata meta = metadataService.extractMetadata(sourceFile);
        int year = meta.year();
        Instant captureInstant = meta.captureInstant();

        if (isPhoto(fileName)) {
            Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("photos");
            String targetFileName = metadataService.generateTargetFileName("photo", captureInstant, fileName);
            copyMediaFileFlattened(sourceFile, targetDir, targetFileName, "photo", year, captureInstant, report);
        } else if (isVideo(fileName)) {
            Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("videos");
            String targetFileName = metadataService.generateTargetFileName("video", captureInstant, fileName);
            copyMediaFileFlattened(sourceFile, targetDir, targetFileName, "video", year, captureInstant, report);
        } else if (isArchiveFile(fileName)) {
            processArchive(sourceFile, baseMemoriesDir, report);
        }
    }

    private void copyMediaFileFlattened(Path sourceFile, Path targetDir, String fileName, String type, int year,
                                        Instant captureInstant, ExtractionReport report) {
        try {
            // Fast duplicate rejection check (size -> sparse hash -> full hash)
            if (duplicateService.isDuplicate(sourceFile)) {
                log.info("Skipping duplicate media content for {} (already extracted)", sourceFile);
                report.duplicate(sourceFile.toString());
                return;
            }

            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(fileName);

            // Check if identical target already exists
            if (Files.exists(targetFile)) {
                String sourceDigest = duplicateService.computeFullDigest(sourceFile);
                if (sourceDigest != null && duplicateService.targetMatchesContent(targetFile, sourceDigest)) {
                    log.info("Skipping duplicate media content for {} (matches target {})", sourceFile, targetFile);
                    duplicateService.registerDigest(sourceDigest);
                    report.duplicate(sourceFile.toString());
                    return;
                }
                targetFile = duplicateService.getUniqueFileName(targetDir, fileName);
            }

            int maxAttempts = 100;
            int attempt = 0;
            while (attempt < maxAttempts) {
                MessageDigest md;
                try {
                    md = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException("SHA-256 algorithm not available", e);
                }

                try {
                    byte[] buffer = new byte[65536]; // 64 KB buffer for high I/O throughput
                    try (InputStream in = Files.newInputStream(sourceFile);
                         OutputStream out = new DigestOutputStream(
                                 Files.newOutputStream(targetFile, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE), md)) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }

                    String digest = toHexString(md.digest());
                    long size = Files.size(targetFile);
                    if (!duplicateService.registerDigest(digest, size, targetFile)) {
                        Files.deleteIfExists(targetFile);
                        log.info("Skipping duplicate media content for {} (digest match)", sourceFile);
                        report.duplicate(sourceFile.toString());
                        return;
                    }

                    if (preserveTimestamps && captureInstant != null) {
                        try {
                            Files.setLastModifiedTime(targetFile, FileTime.fromMillis(captureInstant.toEpochMilli()));
                        } catch (Exception ignored) {
                        }
                    }

                    log.info("Copied media file to: {}", targetFile);
                    report.extracted(type, year, size);
                    Path normalizedTarget = targetFile.toAbsolutePath().normalize();
                    newlyExtractedFiles.add(normalizedTarget);
                    if ("photo".equals(type)) {
                        extractedImagePaths.add(normalizedTarget);
                    }
                    return;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    targetFile = duplicateService.getUniqueFileName(targetDir, fileName);
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

    private void processArchive(Path archiveFile, Path baseMemoriesDir, ExtractionReport report) {
        queuedItems.incrementAndGet();
        log.info("Processing archive: {} (queued items: {})", archiveFile, queuedItems.get());
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(archiveFile), 65536);
             ArchiveInputStream<?> ais = createArchiveInputStream(archiveFile, fis)) {

            int archiveYear = getYearFromFile(archiveFile);

            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                Path entryFileName = Path.of(entryName).getFileName();
                if (entryFileName == null) continue;
                String fileName = entryFileName.toString();

                int year = archiveYear;
                Instant entryInstant = null;
                if (entry.getLastModifiedDate() != null) {
                    year = extractYearFromArchiveEntry(entry);
                    entryInstant = entry.getLastModifiedDate().toInstant();
                }

                if (isPhoto(fileName)) {
                    Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("photos");
                    String targetFileName = metadataService.generateTargetFileName("photo", entryInstant, fileName);
                    copyArchiveEntryFlattened(ais, baseMemoriesDir, targetDir, targetFileName, "photo", year, entryInstant, report);
                } else if (isVideo(fileName)) {
                    Path targetDir = baseMemoriesDir.resolve(String.valueOf(year)).resolve("videos");
                    String targetFileName = metadataService.generateTargetFileName("video", entryInstant, fileName);
                    copyArchiveEntryFlattened(ais, baseMemoriesDir, targetDir, targetFileName, "video", year, entryInstant, report);
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

    private void copyArchiveEntryFlattened(ArchiveInputStream<?> ais, Path baseMemoriesDir, Path targetDir,
                                           String fileName, String type, int year, Instant captureInstant,
                                           ExtractionReport report) {
        Path tempFile = null;
        try {
            Files.createDirectories(targetDir);
            tempFile = Files.createTempFile(targetDir, ".media-entry-", ".tmp");

            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 algorithm not available", e);
            }

            // Stream directly while computing digest on the fly
            byte[] buffer = new byte[65536];
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(tempFile), md)) {
                int read;
                while ((read = ais.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            if (isCorrupted(tempFile, fileName)) {
                log.warn("Skipping corrupted archive entry: {}", fileName);
                report.corrupted(fileName, "archive entry failed validation or is empty");
                if (quarantineEnabled) {
                    Path quarantined = integrityService.quarantineFile(tempFile, baseMemoriesDir, year, type, "corrupted archive entry");
                    if (quarantined != null) {
                        newlyExtractedFiles.add(quarantined.toAbsolutePath().normalize());
                    }
                    report.quarantined(fileName, "quarantined archive entry");
                }
                return;
            }

            String digest = toHexString(md.digest());
            if (duplicateService.isDuplicate(digest)) {
                log.info("Skipping duplicate archived media content for {}", fileName);
                report.duplicate(fileName);
                return;
            }

            Path targetFile = targetDir.resolve(fileName);
            if (Files.exists(targetFile)) {
                if (duplicateService.targetMatchesContent(targetFile, digest)) {
                    duplicateService.registerDigest(digest);
                    report.duplicate(fileName);
                    return;
                }
                targetFile = duplicateService.getUniqueFileName(targetDir, fileName);
            }

            int maxAttempts = 100;
            int attempt = 0;
            while (attempt < maxAttempts) {
                try {
                    byte[] copyBuf = new byte[65536];
                    try (InputStream in = Files.newInputStream(tempFile);
                         OutputStream out = Files.newOutputStream(targetFile, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                        int read;
                        while ((read = in.read(copyBuf)) != -1) {
                            out.write(copyBuf, 0, read);
                        }
                    }

                    Files.deleteIfExists(tempFile);
                    tempFile = null;

                    long size = Files.size(targetFile);
                    if (!duplicateService.registerDigest(digest, size, targetFile)) {
                        Files.deleteIfExists(targetFile);
                        log.info("Skipping duplicate archived media content for {}", fileName);
                        report.duplicate(fileName);
                        return;
                    }

                    if (preserveTimestamps && captureInstant != null) {
                        try {
                            Files.setLastModifiedTime(targetFile, FileTime.fromMillis(captureInstant.toEpochMilli()));
                        } catch (Exception ignored) {
                        }
                    }

                    log.info("Extracted media file to: {}", targetFile);
                    report.extracted(type, year, size);
                    Path normalizedTarget = targetFile.toAbsolutePath().normalize();
                    newlyExtractedFiles.add(normalizedTarget);
                    if ("photo".equals(type)) {
                        extractedImagePaths.add(normalizedTarget);
                    }
                    return;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    targetFile = duplicateService.getUniqueFileName(targetDir, fileName);
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
        return integrityService.createArchiveInputStream(file, is);
    }

    private boolean isPhoto(String fileName) {
        return metadataService.isPhoto(fileName);
    }

    private boolean isVideo(String fileName) {
        return metadataService.isVideo(fileName);
    }

    private boolean isArchiveFile(String fileName) {
        return metadataService.isArchiveFile(fileName);
    }

    private boolean isCorrupted(Path file, String fileName) {
        return integrityService.isCorrupted(file, fileName);
    }

    private Path getUniqueFileName(Path targetDir, String fileName) {
        return duplicateService.getUniqueFileName(targetDir, fileName);
    }

    private int getYearFromFile(Path file) {
        return metadataService.extractYear(file);
    }

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

    public record SanitizationReport(int scanned, int valid, int corrupted, int duplicates, int renamed, int cleanedDirectories) {}

    private record SanitizationCandidate(
            Path file,
            Path typeDir,
            Path yearDir,
            int year,
            String mediaType,
            String fileName
    ) {}

    public SanitizationReport sanitizeMemories(Path baseMemoriesDir) {
        log.info("Starting sanitization of memories directory: {}", baseMemoriesDir);
        duplicateService.clear();

        if (!Files.exists(baseMemoriesDir)) {
            log.warn("Base memories directory does not exist: {}", baseMemoriesDir);
            return new SanitizationReport(0, 0, 0, 0, 0, 0);
        }

        AtomicInteger scannedCount = new AtomicInteger();
        AtomicInteger validCount = new AtomicInteger();
        AtomicInteger corruptedCount = new AtomicInteger();
        AtomicInteger duplicatesCount = new AtomicInteger();
        AtomicInteger renamedCount = new AtomicInteger();
        AtomicInteger cleanedDirs = new AtomicInteger();

        List<SanitizationCandidate> allCandidates = new ArrayList<>();
        List<Path> typeDirectories = new ArrayList<>();
        List<Path> yearDirectories = new ArrayList<>();

        // Discovery phase
        try (var yearEntries = Files.list(baseMemoriesDir)) {
            for (Path yearDir : yearEntries.toList()) {
                if (!Files.isDirectory(yearDir)) continue;
                String yearName = yearDir.getFileName().toString();
                if (yearName.equals("quarantine") || yearName.equals("reports") || yearName.startsWith(".")) {
                    continue;
                }

                yearDirectories.add(yearDir);
                int year;
                try {
                    year = Integer.parseInt(yearName);
                } catch (NumberFormatException e) {
                    year = LocalDateTime.now().getYear();
                }

                for (String mediaType : new String[]{"photo", "video"}) {
                    Path typeDir = yearDir.resolve(mediaType + "s");
                    if (!Files.exists(typeDir) || !Files.isDirectory(typeDir)) continue;
                    typeDirectories.add(typeDir);

                    try (var files = Files.list(typeDir)) {
                        for (Path file : files.toList()) {
                            if (!Files.isRegularFile(file)) continue;
                            scannedCount.incrementAndGet();
                            allCandidates.add(new SanitizationCandidate(
                                    file, typeDir, yearDir, year, mediaType, file.getFileName().toString()
                            ));
                        }
                    } catch (IOException e) {
                        log.error("Error reading directory {}", typeDir, e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error scanning memories directory for sanitization", e);
        }

        if (allCandidates.isEmpty()) {
            pruneDirectories(typeDirectories, yearDirectories, cleanedDirs);
            return new SanitizationReport(0, 0, 0, 0, 0, cleanedDirs.get());
        }

        // Virtual-threaded processing across all media files
        try (ExecutorService vExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Phase 1: Parallel corruption verification
            List<SanitizationCandidate> uncorruptedCandidates = new CopyOnWriteArrayList<>();
            Map<Path, SanitizationCandidate> candidateByPath = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> corruptionFutures = new ArrayList<>();

            for (SanitizationCandidate candidate : allCandidates) {
                candidateByPath.put(candidate.file(), candidate);
                corruptionFutures.add(CompletableFuture.runAsync(() -> {
                    if (integrityService.isCorrupted(candidate.file(), candidate.fileName())) {
                        log.warn("Sanitizing corrupted file in memories: {}", candidate.file());
                        integrityService.quarantineAndRemove(candidate.file(), baseMemoriesDir, candidate.year(),
                                candidate.mediaType(), "corrupted file detected during sanitization");
                        corruptedCount.incrementAndGet();
                    } else {
                        uncorruptedCandidates.add(candidate);
                    }
                }, vExecutor));
            }
            CompletableFuture.allOf(corruptionFutures.toArray(CompletableFuture[]::new)).join();

            // Phase 2: Multi-tier zero-I/O duplicate detection on uncorrupted candidates
            List<Path> uncorruptedPaths = uncorruptedCandidates.stream().map(SanitizationCandidate::file).toList();
            DuplicateDetectionService.DeduplicationResult dedupResult =
                    duplicateService.detectDuplicatesMultiTier(uncorruptedPaths, vExecutor);

            // Quarantine duplicates in parallel using atomic filesystem moves
            List<CompletableFuture<Void>> duplicateFutures = new ArrayList<>();
            for (Path duplicatePath : dedupResult.duplicates()) {
                SanitizationCandidate candidate = candidateByPath.get(duplicatePath);
                if (candidate != null) {
                    duplicateFutures.add(CompletableFuture.runAsync(() -> {
                        log.info("Sanitizing duplicate file in memories: {}", duplicatePath);
                        integrityService.quarantineAndRemove(duplicatePath, baseMemoriesDir, candidate.year(),
                                candidate.mediaType(), "duplicate file detected during sanitization");
                        duplicatesCount.incrementAndGet();
                    }, vExecutor));
                }
            }
            CompletableFuture.allOf(duplicateFutures.toArray(CompletableFuture[]::new)).join();

            // Phase 3: Fast-path naming & renaming pass for valid files
            List<CompletableFuture<Void>> renameFutures = new ArrayList<>();
            for (Path validPath : dedupResult.validFiles()) {
                SanitizationCandidate candidate = candidateByPath.get(validPath);
                if (candidate != null) {
                    validCount.incrementAndGet();
                    renameFutures.add(CompletableFuture.runAsync(() -> {
                        String fileName = candidate.fileName();
                        Path file = candidate.file();
                        Path typeDir = candidate.typeDir();

                        // Fast-path naming check: skip if already formatted
                        if (metadataService.isAlreadyFormatted(fileName)) {
                            return;
                        }

                        // Unformatted file: extract metadata and rename
                        MediaMetadataService.Metadata meta = metadataService.extractMetadata(file);
                        String targetName = metadataService.generateTargetFileName(candidate.mediaType(), meta.captureInstant(), fileName);
                        if (!fileName.equals(targetName)) {
                            Path targetFile = typeDir.resolve(targetName);
                            if (Files.exists(targetFile) && !targetFile.equals(file)) {
                                targetFile = duplicateService.getUniqueFileName(typeDir, targetName);
                            }
                            try {
                                Files.move(file, targetFile, StandardCopyOption.ATOMIC_MOVE);
                                renamedCount.incrementAndGet();
                                log.debug("Renamed memory file: {} -> {}", fileName, targetFile.getFileName());
                            } catch (IOException e) {
                                try {
                                    Files.move(file, targetFile);
                                    renamedCount.incrementAndGet();
                                } catch (IOException ex) {
                                    log.warn("Failed to rename {} to {}: {}", fileName, targetFile, ex.getMessage());
                                }
                            }
                        }
                    }, vExecutor));
                }
            }
            CompletableFuture.allOf(renameFutures.toArray(CompletableFuture[]::new)).join();
        }

        // Phase 4: Directory pruning handling DirectoryNotEmptyException directly without listing streams
        pruneDirectories(typeDirectories, yearDirectories, cleanedDirs);

        SanitizationReport report = new SanitizationReport(
                scannedCount.get(),
                validCount.get(),
                corruptedCount.get(),
                duplicatesCount.get(),
                renamedCount.get(),
                cleanedDirs.get()
        );
        log.info("Sanitization complete: scanned={}, valid={}, corrupted={}, duplicates={}, renamed={}, cleanedDirectories={}",
                report.scanned(), report.valid(), report.corrupted(), report.duplicates(), report.renamed(), report.cleanedDirectories());
        return report;
    }

    private void pruneDirectories(List<Path> typeDirectories, List<Path> yearDirectories, AtomicInteger cleanedDirs) {
        // Prune empty type directories
        for (Path typeDir : typeDirectories) {
            try {
                Files.delete(typeDir);
            } catch (DirectoryNotEmptyException | NoSuchFileException ignored) {
            } catch (IOException e) {
                log.debug("Unable to delete type directory {}: {}", typeDir, e.getMessage());
            }
        }

        // Prune empty year directories
        for (Path yearDir : yearDirectories) {
            try {
                Files.delete(yearDir);
                cleanedDirs.incrementAndGet();
                log.info("Removed empty year directory: {}", yearDir);
            } catch (DirectoryNotEmptyException | NoSuchFileException ignored) {
            } catch (IOException e) {
                log.debug("Unable to delete year directory {}: {}", yearDir, e.getMessage());
            }
        }
    }

    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}