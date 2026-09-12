// MediaExtractorApplication.java
package com.example.mediaextractor;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class MediaExtractorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MediaExtractorApplication.class);

    private final MediaExtractorService mediaExtractorService;
    private final Environment environment;
    private final PythonClassifierProcessService classifierProcessService;
    private final IncrementalBackupService incrementalBackupService;

    @Autowired
    public MediaExtractorApplication(MediaExtractorService mediaExtractorService,
                                     Environment environment,
                                     PythonClassifierProcessService classifierProcessService,
                                     IncrementalBackupService incrementalBackupService) {
        this.mediaExtractorService = mediaExtractorService;
        this.environment = environment;
        this.classifierProcessService = classifierProcessService;
        this.incrementalBackupService = incrementalBackupService;
    }

    public MediaExtractorApplication(MediaExtractorService mediaExtractorService,
                                     Environment environment,
                                     PythonClassifierProcessService classifierProcessService) {
        this(mediaExtractorService, environment, classifierProcessService, new IncrementalBackupService());
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

        String dateFormat = environment.getProperty("media-extractor.date-format", "yyyyMMdd_HHmmss");
        String photoPrefix = environment.getProperty("media-extractor.photo-prefix", "IMG_");
        String videoPrefix = environment.getProperty("media-extractor.video-prefix", "MOV_");
        mediaExtractorService.getMetadataService().setDateFormatPattern(dateFormat);
        mediaExtractorService.getMetadataService().setPhotoPrefix(photoPrefix);
        mediaExtractorService.getMetadataService().setVideoPrefix(videoPrefix);

        ParsedArguments parsed = parseArguments(args);
        boolean sanitizeMode = parsed.sanitizeMode();
        boolean classifyOnly = parsed.classifyOnly();
        Boolean classifierEnabledOverride = parsed.classifierEnabledOverride();
        String classifierActionOverride = parsed.classifierActionOverride();
        Boolean classifierQuarantineOverride = parsed.classifierQuarantineOverride();
        String classifierModeOverride = parsed.classifierModeOverride();
        String sourceArgument = parsed.sourceArgument();
        String outputArgument = parsed.outputArgument();

        String configuredOutputDir = environment.getProperty("media-extractor.output-directory",
                environment.getProperty("media-extractor.output-dir", ""));
        Path baseMemoriesDir = resolveOutputDirectory(outputArgument, configuredOutputDir);

        if (sanitizeMode) {
            log.info("Sanitization mode requested. Sanitizing existing memories at {}", baseMemoriesDir);
            MediaExtractorService.SanitizationReport report = mediaExtractorService.sanitizeMemories(baseMemoriesDir);
            log.info("Sanitization completed: scanned={}, valid={}, corrupted={}, duplicates={}, renamed={}, cleanedDirectories={}",
                    report.scanned(), report.valid(), report.corrupted(), report.duplicates(), report.renamed(), report.cleanedDirectories());
            return;
        }

        Files.createDirectories(baseMemoriesDir);
        log.info("Base output directory ready at {}", baseMemoriesDir);

        boolean shouldExtract = !classifyOnly && !sanitizeMode && (sourceArgument != null || !Boolean.TRUE.equals(classifierEnabledOverride));
        Path sourceDir = null;
        if (sourceArgument != null) {
            sourceDir = expandUserHome(sourceArgument).toAbsolutePath().normalize();
        } else if (shouldExtract) {
            sourceDir = Path.of("C:\\Users\\Shailender\\projects\\backup").toAbsolutePath().normalize();
        }

        ExtractionReportWriter.ReportPaths reportPaths = null;
        if (shouldExtract) {
            log.info("Starting media extraction workflow with sourceDir={}, outputBaseDir={}",
                    sourceDir, baseMemoriesDir);

            if (sourceDir == null || !Files.exists(sourceDir)) {
                log.error("Source directory does not exist: {}", sourceDir);
                System.exit(1);
            }

            int configuredThreads = environment.getProperty("media-extractor.threads", Integer.class, 0);
            int maxInFlight = environment.getProperty("media-extractor.max-in-flight", Integer.class, 256);
            long progressInterval = environment.getProperty("media-extractor.progress-interval-ms", Long.class, 5000L);
            String configuredReportDirectory = environment.getProperty("media-extractor.report-directory", "");
            Path reportDirectory = configuredReportDirectory.isBlank()
                ? baseMemoriesDir
                : expandUserHome(configuredReportDirectory).toAbsolutePath().normalize();

            ExecutorService configuredExecutor = configuredThreads > 0
                ? Executors.newFixedThreadPool(configuredThreads)
                : Executors.newVirtualThreadPerTaskExecutor();
            try (ExecutorService executor = configuredExecutor;
                 ProgressReporter progress = new ProgressReporter(mediaExtractorService.startReport(), progressInterval)) {
                Path tempDir = Files.createTempDirectory("media-extractor");
                log.info("Created temporary directory for archive extraction: {}", tempDir);

                boolean quarantineEnabled = environment.getProperty("media-extractor.quarantine-enabled", Boolean.class, true);
                boolean preserveTimestamps = environment.getProperty("media-extractor.preserve-timestamps", Boolean.class, true);

                mediaExtractorService.setExecutor(executor);
                mediaExtractorService.setTempDir(tempDir);
                mediaExtractorService.setMaxInFlight(maxInFlight);
                mediaExtractorService.setQuarantineEnabled(quarantineEnabled);
                mediaExtractorService.setPreserveTimestamps(preserveTimestamps);
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
                    reportPaths = new ExtractionReportWriter()
                        .write(report, reportDirectory, sourceDir.toString(), baseMemoriesDir.toString());
                    log.info("Extraction report written to JSON={} and HTML={}", reportPaths.json(), reportPaths.html());
                    log.info("Extraction summary: scanned={}, extracted={}, duplicates={}, corrupted={}, quarantined={}, failed={}, durationMs={}, rate={}/s",
                            report.scanned(), report.extracted(), report.duplicates(), report.corrupted(), report.quarantined(), report.failed(),
                            report.durationMillis(), String.format("%.2f", report.filesPerSecond()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for tasks to complete", e);
            } finally {
                log.info("Cleaning up temporary extraction files");
                mediaExtractorService.cleanupTempDir();
            }
        } else {
            log.info("Classifier-only run requested (--classify). Skipping extraction phase and proceeding directly to classification on {}",
                    baseMemoriesDir);
        }

        classifierProcessService.classifyAfterExtraction(
            baseMemoriesDir,
            mediaExtractorService.getExtractedImagePaths(),
            environment,
            new PythonClassifierProcessService.ClassifierOptions(
                classifierEnabledOverride,
                classifierActionOverride,
                classifierQuarantineOverride,
                classifierModeOverride
            ));

        boolean incremental = parsed.incremental() || environment.getProperty("media-extractor.backup.incremental", Boolean.class, false);
        if (incremental) {
            log.info("Incremental backup mode requested. Packaging newly added files into multi-part 7-Zip backup...");
            String backupDirArg = parsed.backupDirArgument();
            String configuredBackupDir = environment.getProperty("media-extractor.backup.dir", "");
            Path backupDir;
            if (backupDirArg != null && !backupDirArg.isBlank()) {
                backupDir = expandUserHome(backupDirArg).toAbsolutePath().normalize();
            } else if (!configuredBackupDir.isBlank()) {
                backupDir = expandUserHome(configuredBackupDir).toAbsolutePath().normalize();
            } else {
                backupDir = baseMemoriesDir.resolve("backups").toAbsolutePath().normalize();
            }

            String partSize = parsed.backupPartSize();
            if (partSize == null || partSize.isBlank()) {
                partSize = environment.getProperty("media-extractor.backup.part-size", "4g");
            }

            Integer compression = parsed.backupCompression();
            if (compression == null) {
                compression = environment.getProperty("media-extractor.backup.compression", Integer.class, 1);
            }

            String sevenZipBinary = parsed.backup7zBinary();
            if (sevenZipBinary == null || sevenZipBinary.isBlank()) {
                sevenZipBinary = environment.getProperty("media-extractor.backup.7z-binary", "/usr/bin/7z");
            }

            List<Path> metadataFiles = new java.util.ArrayList<>();
            if (reportPaths != null) {
                metadataFiles.add(reportPaths.json());
                metadataFiles.add(reportPaths.html());
            }
            metadataFiles.add(baseMemoriesDir.resolve("classification_results.csv"));
            metadataFiles.add(baseMemoriesDir.resolve("review_queue.csv"));

            IncrementalBackupService.BackupResult backupResult = incrementalBackupService.createIncrementalBackup(
                    baseMemoriesDir,
                    mediaExtractorService.getNewlyExtractedFiles(),
                    metadataFiles,
                    backupDir,
                    partSize,
                    compression,
                    sevenZipBinary
            );

            if (backupResult.success()) {
                if (!backupResult.createdVolumes().isEmpty()) {
                    log.info("================================================================================");
                    log.info("INCREMENTAL BACKUP COMPLETE: {} files packaged into {} volume(s)",
                            backupResult.fileCount(), backupResult.createdVolumes().size());
                    log.info("Backup Directory: {}", backupResult.backupDir());
                    for (IncrementalBackupService.BackupVolume vol : backupResult.createdVolumes()) {
                        log.info("  -> {} ({})", vol.fileName(), formatBytes(vol.sizeBytes()));
                    }
                    log.info("Receipt file:     {}", backupResult.receiptPath());
                    log.info("Action Required:  You can now safely copy the volume files above to your external drive.");
                    log.info("================================================================================");
                } else {
                    log.info("Incremental backup completed: No new files to back up.");
                }
            } else {
                log.error("Incremental backup failed: {}", backupResult.message());
            }
        }

        log.info("Media extraction workflow completed");
    }

    public record ParsedArguments(
        boolean sanitizeMode,
        boolean classifyOnly,
        Boolean classifierEnabledOverride,
        String classifierActionOverride,
        Boolean classifierQuarantineOverride,
        String classifierModeOverride,
        String sourceArgument,
        String outputArgument,
        boolean incremental,
        String backupDirArgument,
        String backupPartSize,
        Integer backupCompression,
        String backup7zBinary
    ) {
        public ParsedArguments(
            boolean sanitizeMode,
            boolean classifyOnly,
            Boolean classifierEnabledOverride,
            String classifierActionOverride,
            Boolean classifierQuarantineOverride,
            String classifierModeOverride,
            String sourceArgument,
            String outputArgument
        ) {
            this(
                sanitizeMode,
                classifyOnly,
                classifierEnabledOverride,
                classifierActionOverride,
                classifierQuarantineOverride,
                classifierModeOverride,
                sourceArgument,
                outputArgument,
                false,
                null,
                null,
                null,
                null
            );
        }
    }

    public static ParsedArguments parseArguments(String... args) {
        boolean sanitizeMode = false;
        boolean classifyOnly = false;
        Boolean classifierEnabledOverride = null;
        String classifierActionOverride = null;
        Boolean classifierQuarantineOverride = null;
        String classifierModeOverride = null;
        String sourceArgument = null;
        String outputArgument = null;
        boolean incremental = false;
        String backupDirArgument = null;
        String backupPartSize = null;
        Integer backupCompression = null;
        String backup7zBinary = null;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                String lower = arg.toLowerCase();
                if (lower.equals("--sanitize") || lower.equals("--clean")) {
                    sanitizeMode = true;
                } else if (lower.startsWith("--mode=")) {
                    classifierModeOverride = arg.substring("--mode=".length()).trim();
                } else if (lower.equals("--mode") && i + 1 < args.length) {
                    classifierModeOverride = args[++i].trim();
                } else if (lower.equals("--classify") || lower.equals("--classify-only") || lower.equals("--resume")) {
                    classifyOnly = true;
                    classifierEnabledOverride = true;
                } else if (lower.equals("--no-classify")) {
                    classifierEnabledOverride = false;
                    classifyOnly = false;
                } else if (lower.equals("--move") || lower.equals("--action=move")) {
                    classifierEnabledOverride = true;
                    classifierActionOverride = "move";
                } else if (lower.equals("--dry-run") || lower.equals("--action=dry-run")) {
                    classifierEnabledOverride = true;
                    classifierActionOverride = "dry-run";
                } else if (lower.equals("--copy") || lower.equals("--action=copy")) {
                    classifierEnabledOverride = true;
                    classifierActionOverride = "copy";
                } else if (lower.startsWith("--action=")) {
                    classifierEnabledOverride = true;
                    classifierActionOverride = arg.substring("--action=".length()).trim();
                } else if (lower.equals("--action") && i + 1 < args.length) {
                    classifierEnabledOverride = true;
                    classifierActionOverride = args[++i].trim();
                } else if (lower.equals("--quarantine") || lower.equals("--quarantine-memes")) {
                    classifierQuarantineOverride = true;
                } else if (lower.equals("--no-quarantine")) {
                    classifierQuarantineOverride = false;
                } else if (lower.startsWith("--output=")) {
                    outputArgument = arg.substring("--output=".length()).trim();
                } else if (lower.equals("--output") && i + 1 < args.length) {
                    outputArgument = args[++i].trim();
                } else if (lower.startsWith("--output-dir=")) {
                    outputArgument = arg.substring("--output-dir=".length()).trim();
                } else if (lower.equals("--output-dir") && i + 1 < args.length) {
                    outputArgument = args[++i].trim();
                } else if (lower.startsWith("-o=")) {
                    outputArgument = arg.substring("-o=".length()).trim();
                } else if (lower.equals("-o") && i + 1 < args.length) {
                    outputArgument = args[++i].trim();
                } else if (lower.equals("--incremental")) {
                    incremental = true;
                } else if (lower.startsWith("--backup-dir=")) {
                    backupDirArgument = arg.substring("--backup-dir=".length()).trim();
                    incremental = true;
                } else if (lower.equals("--backup-dir") && i + 1 < args.length) {
                    backupDirArgument = args[++i].trim();
                    incremental = true;
                } else if (lower.startsWith("-b=")) {
                    backupDirArgument = arg.substring("-b=".length()).trim();
                    incremental = true;
                } else if (lower.equals("-b") && i + 1 < args.length) {
                    backupDirArgument = args[++i].trim();
                    incremental = true;
                } else if (lower.startsWith("--backup-part-size=")) {
                    backupPartSize = arg.substring("--backup-part-size=".length()).trim();
                } else if (lower.equals("--backup-part-size") && i + 1 < args.length) {
                    backupPartSize = args[++i].trim();
                } else if (lower.startsWith("--backup-compression=")) {
                    backupCompression = parseCompression(arg.substring("--backup-compression=".length()).trim());
                } else if (lower.equals("--backup-compression") && i + 1 < args.length) {
                    backupCompression = parseCompression(args[++i].trim());
                } else if (lower.startsWith("--backup-7z-binary=")) {
                    backup7zBinary = arg.substring("--backup-7z-binary=".length()).trim();
                } else if (lower.equals("--backup-7z-binary") && i + 1 < args.length) {
                    backup7zBinary = args[++i].trim();
                } else if (!arg.startsWith("-")) {
                    if (sourceArgument == null) {
                        sourceArgument = arg;
                    } else if (outputArgument == null) {
                        outputArgument = arg;
                    }
                }
            }
        }

        if (classifyOnly) {
            if (outputArgument == null && sourceArgument != null) {
                outputArgument = sourceArgument;
            }
            sourceArgument = null;
        }

        return new ParsedArguments(
            sanitizeMode,
            classifyOnly,
            classifierEnabledOverride,
            classifierActionOverride,
            classifierQuarantineOverride,
            classifierModeOverride,
            sourceArgument,
            outputArgument,
            incremental,
            backupDirArgument,
            backupPartSize,
            backupCompression,
            backup7zBinary
        );
    }

    public static Path resolveOutputDirectory(String outputArgument, String configuredProperty) {
        String rawPath = null;
        if (outputArgument != null && !outputArgument.isBlank()) {
            rawPath = outputArgument.trim();
        } else if (configuredProperty != null && !configuredProperty.isBlank()) {
            rawPath = configuredProperty.trim();
        }

        if (rawPath == null || rawPath.isBlank()) {
            return Path.of(System.getProperty("user.home")).resolve("archive").resolve("memories").toAbsolutePath().normalize();
        }

        return expandUserHome(rawPath).toAbsolutePath().normalize();
    }

    public static Path expandUserHome(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return Path.of(System.getProperty("user.home"));
        }
        String trimmed = pathStr.trim();
        String userHome = System.getProperty("user.home");
        if (trimmed.equals("~")) {
            return Path.of(userHome);
        }
        if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            return Path.of(userHome).resolve(trimmed.substring(2));
        }
        return Path.of(trimmed);
    }

    private static Integer parseCompression(String str) {
        if (str == null || str.isBlank()) return null;
        String lower = str.toLowerCase().trim();
        return switch (lower) {
            case "store", "0" -> 0;
            case "fast", "1" -> 1;
            case "normal", "5" -> 5;
            case "maximum", "7" -> 7;
            case "ultra", "9" -> 9;
            default -> {
                try {
                    yield Integer.parseInt(lower);
                } catch (NumberFormatException e) {
                    yield 1;
                }
            }
        };
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "iB";
        return String.format(Locale.ROOT, "%.2f %s", bytes / Math.pow(1024, exp), pre);
    }
}