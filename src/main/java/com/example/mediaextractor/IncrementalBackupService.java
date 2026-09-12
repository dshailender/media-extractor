package com.example.mediaextractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class IncrementalBackupService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalBackupService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public record BackupVolume(
        String fileName,
        long sizeBytes
    ) {}

    public record BackupResult(
        boolean success,
        String archiveBaseName,
        Path backupDir,
        List<BackupVolume> createdVolumes,
        long totalCompressedBytes,
        long totalUncompressedBytes,
        int fileCount,
        long durationMillis,
        Path receiptPath,
        String message
    ) {
        public static BackupResult empty(Path backupDir, String message) {
            return new BackupResult(true, "", backupDir, List.of(), 0, 0, 0, 0, null, message);
        }

        public static BackupResult failure(Path backupDir, String message) {
            return new BackupResult(false, "", backupDir, List.of(), 0, 0, 0, 0, null, message);
        }
    }

    public BackupResult createIncrementalBackup(
            Path baseMemoriesDir,
            List<Path> newlyExtractedFiles,
            List<Path> metadataFiles,
            Path backupDir) throws IOException, InterruptedException {
        return createIncrementalBackup(baseMemoriesDir, newlyExtractedFiles, metadataFiles, backupDir, "4g", 1, "/usr/bin/7z");
    }

    public BackupResult createIncrementalBackup(
            Path baseMemoriesDir,
            List<Path> newlyExtractedFiles,
            List<Path> metadataFiles,
            Path backupDir,
            String partSize,
            int compressionLevel,
            String sevenZipBinary) throws IOException, InterruptedException {

        long startTime = System.currentTimeMillis();
        Path normalizedMemoriesDir = baseMemoriesDir.toAbsolutePath().normalize();
        Path normalizedBackupDir = backupDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedBackupDir);

        log.info("Starting incremental 7-Zip backup preparation in {}", normalizedBackupDir);

        // 1. Resolve final locations for all newly extracted files and metadata
        Map<String, Path> relocationMap = loadRelocationsFromCsv(normalizedMemoriesDir);
        Set<Path> finalFilesToPackage = new LinkedHashSet<>();
        long totalUncompressedBytes = 0;

        if (newlyExtractedFiles != null) {
            for (Path rawPath : newlyExtractedFiles) {
                if (rawPath == null) continue;
                Path resolved = resolveFinalPath(rawPath, normalizedMemoriesDir, relocationMap);
                if (resolved != null && Files.exists(resolved) && !Files.isDirectory(resolved)) {
                    if (finalFilesToPackage.add(resolved)) {
                        totalUncompressedBytes += Files.size(resolved);
                    }
                } else {
                    log.warn("Extracted file not found at {} nor in relocation map/quarantine; skipping", rawPath);
                }
            }
        }

        if (metadataFiles != null) {
            for (Path meta : metadataFiles) {
                if (meta != null && Files.exists(meta) && !Files.isDirectory(meta)) {
                    Path normalizedMeta = meta.toAbsolutePath().normalize();
                    if (finalFilesToPackage.add(normalizedMeta)) {
                        totalUncompressedBytes += Files.size(normalizedMeta);
                    }
                }
            }
        }

        if (finalFilesToPackage.isEmpty()) {
            log.info("No newly extracted files or metadata to package into incremental backup.");
            return BackupResult.empty(normalizedBackupDir, "No files to package");
        }

        log.info("Resolved {} unique files ({} bytes uncompressed) for incremental packaging",
                finalFilesToPackage.size(), totalUncompressedBytes);

        // 2. Build relative path manifest
        Path manifestFile = Files.createTempFile("incremental-backup-manifest-", ".txt");
        List<String> relativePaths = new ArrayList<>();
        for (Path file : finalFilesToPackage) {
            String relative;
            if (file.startsWith(normalizedMemoriesDir)) {
                relative = normalizedMemoriesDir.relativize(file).toString().replace('\\', '/');
            } else {
                relative = file.getFileName().toString();
            }
            relativePaths.add(relative);
        }
        Files.write(manifestFile, relativePaths, StandardCharsets.UTF_8);

        // 3. Prepare 7-Zip archive name & command
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String archiveBaseName = "memories_incremental_" + timestamp + ".7z";
        Path targetArchive = normalizedBackupDir.resolve(archiveBaseName);

        String effectivePartSize = (partSize == null || partSize.isBlank()) ? "4g" : partSize.trim();
        int effectiveCompression = (compressionLevel >= 0 && compressionLevel <= 9) ? compressionLevel : 1;
        String effective7z = (sevenZipBinary == null || sevenZipBinary.isBlank()) ? "/usr/bin/7z" : sevenZipBinary.trim();

        List<String> command = new ArrayList<>(List.of(
                effective7z, "a",
                "-v" + effectivePartSize,
                "-mx=" + effectiveCompression,
                "-mmt=on",
                targetArchive.toString(),
                "@" + manifestFile.toAbsolutePath()
        ));

        log.info("Executing 7-Zip command: {}", String.join(" ", command));

        try {
            Process process = new ProcessBuilder(command)
                    .directory(normalizedMemoriesDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    log.debug("[7z] {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("7-Zip archive creation failed with exit code {}:\n{}", exitCode, output);
                throw new IOException("7-Zip command failed with exit code " + exitCode + ":\n" + output);
            }

            // 4. Verify created volume parts & run integrity test
            List<BackupVolume> createdVolumes = findCreatedVolumes(normalizedBackupDir, archiveBaseName);
            if (createdVolumes.isEmpty()) {
                throw new IOException("7-Zip reported success but no archive volume parts were found starting with " + archiveBaseName);
            }

            long totalCompressedBytes = createdVolumes.stream().mapToLong(BackupVolume::sizeBytes).sum();
            log.info("7-Zip created {} volume part(s) totaling {} bytes", createdVolumes.size(), totalCompressedBytes);

            // Test integrity using 7z t
            Path testTarget = normalizedBackupDir.resolve(createdVolumes.get(0).fileName());
            verifyArchiveIntegrity(effective7z, testTarget, normalizedMemoriesDir);

            long duration = System.currentTimeMillis() - startTime;

            // 5. Generate JSON Receipt
            Path receiptFile = normalizedBackupDir.resolve("incremental-backup-receipt-" + timestamp + ".json");
            writeReceiptJson(receiptFile, timestamp, archiveBaseName, normalizedBackupDir, effectivePartSize,
                    effectiveCompression, finalFilesToPackage.size(), totalUncompressedBytes, totalCompressedBytes,
                    duration, createdVolumes, relativePaths);

            log.info("Incremental backup completed successfully: {} volume(s) in {} (receipt: {})",
                    createdVolumes.size(), normalizedBackupDir, receiptFile.getFileName());

            return new BackupResult(
                    true,
                    archiveBaseName,
                    normalizedBackupDir,
                    createdVolumes,
                    totalCompressedBytes,
                    totalUncompressedBytes,
                    finalFilesToPackage.size(),
                    duration,
                    receiptFile,
                    "Incremental backup completed successfully"
            );
        } finally {
            try {
                Files.deleteIfExists(manifestFile);
            } catch (Exception ignored) {
            }
        }
    }

    private void verifyArchiveIntegrity(String sevenZipBinary, Path firstVolume, Path workingDir) throws IOException, InterruptedException {
        log.info("Testing 7-Zip archive integrity for {}", firstVolume.getFileName());
        List<String> testCommand = List.of(sevenZipBinary, "t", firstVolume.toString());
        Process testProcess = new ProcessBuilder(testCommand)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();

        StringBuilder testOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(testProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                testOutput.append(line).append(System.lineSeparator());
            }
        }

        int testExit = testProcess.waitFor();
        if (testExit != 0) {
            log.error("Archive integrity test failed for {}:\n{}", firstVolume, testOutput);
            throw new IOException("Archive integrity verification failed for " + firstVolume.getFileName() + ": " + testOutput);
        }
        log.info("Archive integrity verified successfully for {}", firstVolume.getFileName());
    }

    private List<BackupVolume> findCreatedVolumes(Path backupDir, String archiveBaseName) throws IOException {
        List<BackupVolume> volumes = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupDir)) {
            List<Path> matching = stream
                    .filter(p -> p.getFileName().toString().startsWith(archiveBaseName))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path p : matching) {
                volumes.add(new BackupVolume(p.getFileName().toString(), Files.size(p)));
            }
        }
        return volumes;
    }

    private Path resolveFinalPath(Path originalPath, Path baseMemoriesDir, Map<String, Path> relocationMap) {
        Path norm = originalPath.toAbsolutePath().normalize();
        if (Files.exists(norm)) {
            return norm;
        }

        // Check if relocation map has it
        String key = norm.toString();
        if (relocationMap.containsKey(key)) {
            Path relocated = relocationMap.get(key);
            if (Files.exists(relocated)) {
                return relocated;
            }
        }

        // Search quarantine under baseMemoriesDir for file with the same name
        String fileName = norm.getFileName().toString();
        Path quarantineDir = baseMemoriesDir.resolve("quarantine");
        if (Files.isDirectory(quarantineDir)) {
            try (Stream<Path> walk = Files.walk(quarantineDir, 4)) {
                Optional<Path> found = walk
                        .filter(p -> p.getFileName().toString().equals(fileName))
                        .findFirst();
                if (found.isPresent()) {
                    return found.get().toAbsolutePath().normalize();
                }
            } catch (IOException ignored) {
            }
        }

        return null;
    }

    private Map<String, Path> loadRelocationsFromCsv(Path baseMemoriesDir) {
        Map<String, Path> relocations = new HashMap<>();
        Path csvPath = baseMemoriesDir.resolve("classification_results.csv");
        if (!Files.exists(csvPath)) {
            return relocations;
        }

        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            boolean isHeader = true;
            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                if (line.isBlank()) continue;
                List<String> tokens = parseCsvLine(line);
                if (tokens.size() > 15) {
                    String original = tokens.get(0).trim();
                    String relocated = tokens.get(15).trim();
                    if (!original.isEmpty() && !relocated.isEmpty()) {
                        Path relPath = Path.of(relocated).toAbsolutePath().normalize();
                        if (Files.exists(relPath)) {
                            relocations.put(Path.of(original).toAbsolutePath().normalize().toString(), relPath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse classification_results.csv for relocations: {}", e.getMessage());
        }

        return relocations;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString());
        return tokens;
    }

    private void writeReceiptJson(
            Path receiptFile,
            String timestamp,
            String archiveBaseName,
            Path backupDir,
            String partSize,
            int compressionLevel,
            int filesPackaged,
            long uncompressedBytes,
            long compressedBytes,
            long durationMillis,
            List<BackupVolume> volumes,
            List<String> files) throws IOException {

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"timestamp\": \"").append(timestamp).append("\",\n");
        json.append("  \"archiveBaseName\": \"").append(archiveBaseName).append("\",\n");
        json.append("  \"backupDir\": \"").append(backupDir.toString().replace('\\', '/')).append("\",\n");
        json.append("  \"partSize\": \"").append(partSize).append("\",\n");
        json.append("  \"compressionLevel\": ").append(compressionLevel).append(",\n");
        json.append("  \"filesPackaged\": ").append(filesPackaged).append(",\n");
        json.append("  \"uncompressedBytes\": ").append(uncompressedBytes).append(",\n");
        json.append("  \"compressedBytes\": ").append(compressedBytes).append(",\n");
        json.append("  \"durationMillis\": ").append(durationMillis).append(",\n");
        json.append("  \"volumes\": [\n");
        for (int i = 0; i < volumes.size(); i++) {
            BackupVolume v = volumes.get(i);
            json.append("    {\"fileName\": \"").append(v.fileName()).append("\", \"sizeBytes\": ").append(v.sizeBytes()).append("}");
            if (i < volumes.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");
        json.append("  \"files\": [\n");
        for (int i = 0; i < files.size(); i++) {
            json.append("    \"").append(files.get(i).replace('\\', '/')).append("\"");
            if (i < files.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");

        Files.writeString(receiptFile, json.toString(), StandardCharsets.UTF_8);
    }
}
