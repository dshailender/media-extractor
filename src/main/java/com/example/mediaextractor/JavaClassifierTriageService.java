package com.example.mediaextractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * High-throughput, virtual-thread-powered Java triage and safe staging service.
 * <p>
 * Certifies obvious camera photos in Java (< 0.5 ms/image using EXIF and header dimensions)
 * to bypass expensive Python OCR/CLIP processing, while safely staging non-camera, screenshot,
 * ambiguous, or candidate images for authoritative Python classification.
 */
@Service
public class JavaClassifierTriageService {

    private static final Logger log = LoggerFactory.getLogger(JavaClassifierTriageService.class);

    public static final Set<String> CAMERA_SUPPORTED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "heic", "heif", "avif", "dng", "cr2", "nef", "arw"
    );

    private static final Set<String> KNOWN_EDITOR_KEYWORDS = Set.of(
            "whatsapp", "photoshop", "canva", "picsart", "snapseed", "inshot",
            "meme", "gimp", "vsco", "lightroom", "instagram", "facebook",
            "twitter", "tiktok", "pixlr"
    );

    private static final Set<String> SCREENSHOT_FILENAME_KEYWORDS = Set.of(
            "screenshot", "screen_shot", "screen-shot", "capture", "meme",
            "wa_", "whatsapp", "sticker", "edited"
    );

    private final MediaMetadataService metadataService;

    public enum TriageCategory {
        PHOTO_CERTAIN,
        NEEDS_PYTHON,
        UNSUPPORTED
    }

    public record TriageDecision(
            Path originalPath,
            int year,
            TriageCategory category,
            String reason,
            Path stagedPath,
            int width,
            int height
    ) {
        public TriageDecision withStagedPath(Path newStagedPath) {
            return new TriageDecision(originalPath, year, category, reason, newStagedPath, width, height);
        }
    }

    public record TriageSummary(
            int totalInspected,
            int certifiedPhotos,
            int stagedForPython,
            Path stagingDir,
            Path manifestPath,
            List<TriageDecision> decisions,
            long durationMs
    ) {}

    public JavaClassifierTriageService(MediaMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /**
     * Triages a single image file conservatively.
     * Only returns PHOTO_CERTAIN if genuine camera hardware EXIF is present,
     * geometry is valid, and no screenshot/editing indicators exist.
     */
    public TriageDecision triage(Path file, int year) {
        if (file == null || !Files.isRegularFile(file)) {
            return new TriageDecision(file, year, TriageCategory.UNSUPPORTED, "File does not exist or is not a regular file", null, 0, 0);
        }

        String fileName = file.getFileName().toString();
        String ext = metadataService.getExtension(fileName);
        if (ext == null) {
            return new TriageDecision(file, year, TriageCategory.UNSUPPORTED, "Missing file extension", null, 0, 0);
        }

        ext = ext.toLowerCase(Locale.ROOT);
        if (!CAMERA_SUPPORTED_EXTENSIONS.contains(ext)) {
            return new TriageDecision(file, year, TriageCategory.NEEDS_PYTHON,
                    "Non-camera container format: " + ext + " (requires Python multi-modal evaluation)", null, 0, 0);
        }

        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        for (String keyword : SCREENSHOT_FILENAME_KEYWORDS) {
            if (lowerFileName.contains(keyword)) {
                return new TriageDecision(file, year, TriageCategory.NEEDS_PYTHON,
                        "Filename matches screenshot/social pattern keyword: '" + keyword + "'", null, 0, 0);
            }
        }

        int[] dims = readDimensions(file);
        int width = dims != null ? dims[0] : 0;
        int height = dims != null ? dims[1] : 0;

        if (dims == null || width < 640 || height < 640) {
            return new TriageDecision(file, year, TriageCategory.NEEDS_PYTHON,
                    "Dimensions too small or unreadable: " + (dims != null ? width + "x" + height : "header-unread"),
                    null, width, height);
        }

        double ar = (double) width / height;
        if ((ar >= 0.45 && ar <= 0.60) || (ar >= 1.65 && ar <= 2.25)) {
            return new TriageDecision(file, year, TriageCategory.NEEDS_PYTHON,
                    String.format(Locale.ROOT, "Potential smartphone screenshot aspect ratio: %.3f (%dx%d)", ar, width, height),
                    null, width, height);
        }

        if (Math.abs(ar - 1.0) <= 0.02) {
            try {
                long size = Files.size(file);
                if (size < 500_000L || width < 1080) {
                    return new TriageDecision(file, year, TriageCategory.NEEDS_PYTHON,
                            String.format(Locale.ROOT, "Small square social image format: %dx%d (%d bytes)", width, height, size),
                            null, width, height);
                }
            } catch (IOException ignored) {
            }
        }

        MediaMetadataService.CameraExifInfo exif = metadataService.extractCameraExif(file);
        if (exif == null || !exif.hasCameraHardware()) {
            return new TriageDecision(file, year, TriageCategory.NEEDS_PYTHON,
                    "Missing camera hardware EXIF (Make/Model tags absent)", null, width, height);
        }

        if (exif.software() != null && !exif.software().isBlank()) {
            String lowerSoftware = exif.software().toLowerCase(Locale.ROOT);
            for (String kw : KNOWN_EDITOR_KEYWORDS) {
                if (lowerSoftware.contains(kw)) {
                    return new TriageDecision(file, year, TriageCategory.NEEDS_PYTHON,
                            "Image edited by software: " + exif.software().trim(), null, width, height);
                }
            }
        }

        String dev = exif.deviceDescription();
        String reason = "Certified camera photo (Device: " + (dev != null ? dev : "Camera") + ", " + width + "x" + height + ")";
        return new TriageDecision(file, year, TriageCategory.PHOTO_CERTAIN, reason, null, width, height);
    }

    /**
     * Concurrently triages the given image paths using Java virtual threads,
     * staging candidate images to ~/.staging-{runId}/ and writing an enriched manifest.
     */
    public TriageSummary triageAndStage(List<Path> imagePaths, Path baseMemoriesDir, String action, int maxConcurrency) {
        long startTime = System.currentTimeMillis();
        if (imagePaths == null || imagePaths.isEmpty()) {
            return new TriageSummary(0, 0, 0, null, null, List.of(), 0);
        }

        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path stagingDir = baseMemoriesDir.resolve(".staging-" + runId).toAbsolutePath().normalize();
        boolean isDryRun = "dry-run".equalsIgnoreCase(action);

        ConcurrentLinkedQueue<TriageDecision> initialDecisions = new ConcurrentLinkedQueue<>();
        int permits = maxConcurrency > 0 ? maxConcurrency : 256;
        Semaphore semaphore = new Semaphore(permits);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Path imagePath : imagePaths) {
                executor.submit(() -> {
                    try {
                        semaphore.acquire();
                        int year = extractYearFromPhotosPath(imagePath);
                        TriageDecision decision = triage(imagePath, year);
                        initialDecisions.add(decision);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        semaphore.release();
                    }
                });
            }
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                log.warn("Triage tasks did not terminate within 1 hour");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Triage execution was interrupted");
        }

        List<TriageDecision> stagedDecisions = new ArrayList<>();
        int certifiedCount = 0;
        int candidateCount = 0;

        for (TriageDecision d : initialDecisions) {
            if (d.category() == TriageCategory.PHOTO_CERTAIN) {
                certifiedCount++;
                stagedDecisions.add(d);
            } else {
                candidateCount++;
                stagedDecisions.add(d);
            }
        }

        Path manifestPath = null;
        if (candidateCount > 0) {
            try {
                if (!isDryRun) {
                    Files.createDirectories(stagingDir);
                    trySetPosixOwnerOnly(stagingDir);
                }

                manifestPath = isDryRun
                        ? Files.createTempFile("media-extractor-classifier-dryrun-", ".jsonl")
                        : stagingDir.resolve("manifest.jsonl");

                List<TriageDecision> finalDecisions = new ArrayList<>(stagedDecisions.size());

                try (BufferedWriter writer = Files.newBufferedWriter(manifestPath, StandardCharsets.UTF_8)) {
                    for (TriageDecision d : stagedDecisions) {
                        if (d.category() == TriageCategory.PHOTO_CERTAIN) {
                            finalDecisions.add(d);
                            continue;
                        }

                        Path stagedPath;
                        if (isDryRun) {
                            stagedPath = d.originalPath();
                        } else {
                            String baseName = d.originalPath().getFileName().toString();
                            String uniqueName = UUID.randomUUID().toString().substring(0, 8) + "_" + baseName;
                            stagedPath = stagingDir.resolve(uniqueName);
                            try {
                                Files.move(d.originalPath(), stagedPath, StandardCopyOption.ATOMIC_MOVE);
                            } catch (Exception moveEx) {
                                Files.move(d.originalPath(), stagedPath, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }

                        TriageDecision updated = d.withStagedPath(stagedPath);
                        finalDecisions.add(updated);

                        long fileSize = 0L;
                        long mtimeNs = 0L;
                        try {
                            fileSize = Files.size(stagedPath);
                            mtimeNs = Files.getLastModifiedTime(stagedPath).toInstant().getEpochSecond() * 1_000_000_000L
                                    + Files.getLastModifiedTime(stagedPath).toInstant().getNano();
                        } catch (Exception ignored) {
                        }

                        String jsonLine = String.format(
                                "{\"staged_path\":%s,\"original_path\":%s,\"original_year\":%d,\"triage_category\":%s,\"triage_reason\":%s,\"file_size\":%d,\"mtime_ns\":%d}%n",
                                jsonQuote(stagedPath.toAbsolutePath().normalize().toString()),
                                jsonQuote(d.originalPath().toAbsolutePath().normalize().toString()),
                                d.year(),
                                jsonQuote(d.category().name()),
                                jsonQuote(d.reason()),
                                fileSize,
                                mtimeNs
                        );
                        writer.write(jsonLine);
                    }
                }

                stagedDecisions = finalDecisions;
            } catch (IOException e) {
                log.error("Failed to prepare staging directory or manifest: {}", e.getMessage(), e);
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("Java Triage completed in {} ms: total={}, certifiedPhotos={} (bypassing Python), candidateImages={}",
                durationMs, imagePaths.size(), certifiedCount, candidateCount);

        return new TriageSummary(
                imagePaths.size(),
                certifiedCount,
                candidateCount,
                isDryRun ? null : stagingDir,
                manifestPath,
                Collections.unmodifiableList(stagedDecisions),
                durationMs
        );
    }

    /**
     * Safety recovery guarantee: restores any files remaining in the staging directory
     * back to their original photo path.
    /**
     * Safety recovery guarantee: restores any files remaining in the staging directory
     * back to their original photo path.
     */
    public List<Path> restoreStrandedFilesWithPaths(TriageSummary summary) {
        if (summary == null || summary.decisions() == null || summary.decisions().isEmpty()) {
            return List.of();
        }

        List<Path> restored = new ArrayList<>();
        for (TriageDecision decision : summary.decisions()) {
            Path staged = decision.stagedPath();
            if (staged != null && Files.exists(staged) && !staged.equals(decision.originalPath())) {
                try {
                    Path dest = decision.originalPath();
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    if (Files.exists(dest)) {
                        if (Files.size(staged) == Files.size(dest) && areFileContentsEqual(staged, dest)) {
                            Files.deleteIfExists(staged);
                            restored.add(dest.toAbsolutePath().normalize());
                            continue;
                        }
                        dest = getUniqueDestinationPath(dest.getParent(), dest.getFileName().toString());
                    }
                    try {
                        Files.move(staged, dest, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception e) {
                        if (!Files.exists(dest)) {
                            Files.move(staged, dest);
                        } else {
                            dest = getUniqueDestinationPath(dest.getParent(), dest.getFileName().toString());
                            Files.move(staged, dest);
                        }
                    }
                    restored.add(dest.toAbsolutePath().normalize());
                    log.info("Safety restored stranded staged file {} back to {}", staged, dest);
                } catch (Exception e) {
                    log.error("CRITICAL: Failed to restore stranded file {}: {}", staged, e.getMessage(), e);
                }
            }
        }
        return restored;
    }

    public int restoreStrandedFiles(TriageSummary summary) {
        return restoreStrandedFilesWithPaths(summary).size();
    }

    /**
     * Cleans up the staging directory and temporary manifest if they exist.
     */
    public void cleanupStagingDir(TriageSummary summary) {
        if (summary == null) {
            return;
        }
        if (summary.stagingDir() != null && Files.exists(summary.stagingDir())) {
            try {
                try (var stream = Files.list(summary.stagingDir())) {
                    List<Path> remaining = stream.toList();
                    for (Path rem : remaining) {
                        try {
                            Files.deleteIfExists(rem);
                        } catch (IOException ignored) {
                        }
                    }
                }
                Files.deleteIfExists(summary.stagingDir());
                log.info("Cleaned up staging directory: {}", summary.stagingDir());
            } catch (IOException e) {
                log.warn("Could not completely delete staging directory {}: {}", summary.stagingDir(), e.getMessage());
            }
        }
        if (summary.manifestPath() != null && Files.exists(summary.manifestPath())) {
            try {
                Files.deleteIfExists(summary.manifestPath());
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Discovers orphan .staging-* directories left by abruptly stopped runs,
     * reads their manifests, and recovers/restores candidate files safely back
     * to memories photos directories without overwriting existing files.
     */
    public List<Path> recoverStaleStagingDirectoriesWithPaths(Path baseMemoriesDir) {
        if (baseMemoriesDir == null || !Files.isDirectory(baseMemoriesDir)) {
            return List.of();
        }

        List<Path> totalRecovered = new ArrayList<>();
        try (var stream = Files.list(baseMemoriesDir)) {
            List<Path> stagingDirs = stream
                    .filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().startsWith(".staging-"))
                    .toList();

            for (Path stagingDir : stagingDirs) {
                totalRecovered.addAll(recoverStaleStagingDirectoryWithPaths(stagingDir, baseMemoriesDir));
            }
        } catch (IOException e) {
            log.error("Failed to scan for stale staging directories in {}: {}", baseMemoriesDir, e.getMessage(), e);
        }

        return totalRecovered;
    }

    public int recoverStaleStagingDirectories(Path baseMemoriesDir) {
        return recoverStaleStagingDirectoriesWithPaths(baseMemoriesDir).size();
    }

    public List<Path> recoverStaleStagingDirectoryWithPaths(Path stagingDir, Path baseMemoriesDir) {
        if (stagingDir == null || !Files.isDirectory(stagingDir)) {
            return List.of();
        }

        log.info("Recovering orphan staging directory: {}", stagingDir);
        Path manifestPath = stagingDir.resolve("manifest.jsonl");
        List<Path> recovered = new ArrayList<>();
        Set<Path> handledStagedFiles = new java.util.HashSet<>();

        if (Files.exists(manifestPath)) {
            try (BufferedReader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || !line.startsWith("{")) {
                        continue;
                    }

                    String stagedStr = extractJsonStringField(line, "staged_path");
                    String origStr = extractJsonStringField(line, "original_path");
                    int origYear = extractJsonIntField(line, "original_year", 0);

                    if (stagedStr != null) {
                        Path stagedPath = Path.of(stagedStr);
                        if (!Files.exists(stagedPath)) {
                            stagedPath = stagingDir.resolve(stagedPath.getFileName().toString());
                        }

                        if (Files.exists(stagedPath)) {
                            handledStagedFiles.add(stagedPath.toAbsolutePath().normalize());
                            Path originalPath = origStr != null ? Path.of(origStr) : null;
                            if (originalPath == null) {
                                int year = origYear > 0 ? origYear : extractYearFromPhotosPath(stagedPath);
                                originalPath = baseMemoriesDir.resolve(String.valueOf(year)).resolve("photos").resolve(stagedPath.getFileName().toString());
                            }

                            Path restored = safeRestoreFile(stagedPath, originalPath);
                            if (restored != null) {
                                recovered.add(restored.toAbsolutePath().normalize());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Failed to parse manifest {} in stale staging dir: {}", manifestPath, e.getMessage(), e);
            }
        }

        // Check for any loose files not handled by manifest
        try (var fileStream = Files.list(stagingDir)) {
            List<Path> looseFiles = fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".jsonl"))
                    .filter(p -> !handledStagedFiles.contains(p.toAbsolutePath().normalize()))
                    .toList();

            for (Path loose : looseFiles) {
                String name = loose.getFileName().toString();
                String targetName = name;
                if (name.length() > 9 && name.charAt(8) == '_') {
                    targetName = name.substring(9);
                }
                int year = extractYearFromPhotosPath(loose);
                Path dest = baseMemoriesDir.resolve(String.valueOf(year)).resolve("photos").resolve(targetName);
                Path restored = safeRestoreFile(loose, dest);
                if (restored != null) {
                    recovered.add(restored.toAbsolutePath().normalize());
                }
            }
        } catch (IOException e) {
            log.error("Error inspecting loose files in {}: {}", stagingDir, e.getMessage(), e);
        }

        // Cleanup staging directory once files are restored
        try {
            Files.deleteIfExists(manifestPath);
            try (var checkStream = Files.list(stagingDir)) {
                List<Path> remaining = checkStream.toList();
                if (remaining.isEmpty()) {
                    Files.deleteIfExists(stagingDir);
                    log.info("Safely deleted empty recovered staging directory: {}", stagingDir);
                } else {
                    log.warn("Staging directory {} still contains {} items; keeping directory", stagingDir, remaining.size());
                }
            }
        } catch (IOException e) {
            log.warn("Could not delete staging directory {}: {}", stagingDir, e.getMessage());
        }

        return recovered;
    }

    public int recoverStaleStagingDirectory(Path stagingDir, Path baseMemoriesDir) {
        return recoverStaleStagingDirectoryWithPaths(stagingDir, baseMemoriesDir).size();
    }

    private static Path safeRestoreFile(Path source, Path target) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path dest = target;
            if (Files.exists(dest)) {
                if (Files.size(source) == Files.size(dest) && areFileContentsEqual(source, dest)) {
                    Files.deleteIfExists(source);
                    return dest;
                }
                dest = getUniqueDestinationPath(target.getParent(), target.getFileName().toString());
            }
            try {
                Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                if (!Files.exists(dest)) {
                    Files.move(source, dest);
                } else {
                    dest = getUniqueDestinationPath(dest.getParent(), dest.getFileName().toString());
                    Files.move(source, dest);
                }
            }
            log.info("Safely restored staged file {} -> {}", source, dest);
            return dest;
        } catch (IOException e) {
            log.error("CRITICAL: Failed to restore staged file {}: {}", source, e.getMessage(), e);
            return null;
        }
    }


    public static boolean areFileContentsEqual(Path path1, Path path2) {
        try {
            long size1 = Files.size(path1);
            long size2 = Files.size(path2);
            if (size1 != size2) {
                return false;
            }
            if (size1 == 0) {
                return true;
            }
            try (var is1 = Files.newInputStream(path1);
                 var is2 = Files.newInputStream(path2)) {
                byte[] buf1 = new byte[8192];
                byte[] buf2 = new byte[8192];
                int n1;
                while ((n1 = is1.read(buf1)) > 0) {
                    int n2 = 0;
                    while (n2 < n1) {
                        int r = is2.read(buf2, n2, n1 - n2);
                        if (r < 0) return false;
                        n2 += r;
                    }
                    if (!Arrays.equals(buf1, 0, n1, buf2, 0, n1)) {
                        return false;
                    }
                }
                return is2.read() < 0;
            }
        } catch (IOException e) {
            return false;
        }
    }

    static String extractJsonStringField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return null;
        int colonIdx = json.indexOf(":", idx + field.length() + 2);
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf("\"", colonIdx);
        if (quoteStart < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = quoteStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static int extractJsonIntField(String json, String field, int defaultValue) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(":", idx + field.length() + 2);
        if (colonIdx < 0) return defaultValue;
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (start < end) {
            try {
                return Integer.parseInt(json.substring(start, end));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    static double extractJsonDoubleField(String json, String field, double defaultValue) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(":", idx + field.length() + 2);
        if (colonIdx < 0) return defaultValue;
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '.')) {
            end++;
        }
        if (start < end) {
            try {
                return Double.parseDouble(json.substring(start, end));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    /**
     * Fast header-only image dimension reader using javax.imageio.ImageReader.
     * Does not decode pixel rasters into memory.
     */
    public int[] readDimensions(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(in, true, true);
                        int width = reader.getWidth(0);
                        int height = reader.getHeight(0);
                        if (width > 0 && height > 0) {
                            return new int[]{width, height};
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static int extractYearFromPhotosPath(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null && parent.getFileName().toString().equalsIgnoreCase("photos")) {
                Path yearDir = parent.getParent();
                if (yearDir != null) {
                    return Integer.parseInt(yearDir.getFileName().toString());
                }
            }
        } catch (Exception ignored) {
        }

        for (Path part : path) {
            String name = part.toString();
            if (name.matches("^(19|20)\\d{2}$")) {
                try {
                    return Integer.parseInt(name);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return LocalDate.now().getYear();
    }

    private static Path getUniqueDestinationPath(Path targetDir, String fileName) {
        Path direct = targetDir.resolve(fileName);
        if (!Files.exists(direct)) {
            return direct;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot >= 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        int counter = 1;
        while (true) {
            Path candidate = targetDir.resolve(stem + "_" + counter + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    private static void trySetPosixOwnerOnly(Path dir) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
            if (view != null) {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (Exception ignored) {
        }
    }

    private static String jsonQuote(String string) {
        if (string == null || string.isEmpty()) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(string.length() + 8);
        sb.append('"');
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            switch (c) {
                case '\\':
                case '"':
                    sb.append('\\').append(c);
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
