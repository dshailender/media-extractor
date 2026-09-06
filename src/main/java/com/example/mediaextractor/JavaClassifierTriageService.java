package com.example.mediaextractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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

                        String jsonLine = String.format(
                                "{\"staged_path\":%s,\"original_path\":%s,\"original_year\":%d,\"triage_category\":%s,\"triage_reason\":%s}%n",
                                jsonQuote(stagedPath.toAbsolutePath().normalize().toString()),
                                jsonQuote(d.originalPath().toAbsolutePath().normalize().toString()),
                                d.year(),
                                jsonQuote(d.category().name()),
                                jsonQuote(d.reason())
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
     */
    public int restoreStrandedFiles(TriageSummary summary) {
        if (summary == null || summary.decisions() == null || summary.decisions().isEmpty()) {
            return 0;
        }

        int restored = 0;
        for (TriageDecision decision : summary.decisions()) {
            Path staged = decision.stagedPath();
            if (staged != null && Files.exists(staged) && !staged.equals(decision.originalPath())) {
                try {
                    Path dest = decision.originalPath();
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    if (Files.exists(dest)) {
                        dest = getUniqueDestinationPath(dest.getParent(), dest.getFileName().toString());
                    }
                    try {
                        Files.move(staged, dest, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception e) {
                        Files.move(staged, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    restored++;
                    log.info("Safety restored stranded staged file {} back to {}", staged, dest);
                } catch (Exception e) {
                    log.error("CRITICAL: Failed to restore stranded file {}: {}", staged, e.getMessage(), e);
                }
            }
        }
        return restored;
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
