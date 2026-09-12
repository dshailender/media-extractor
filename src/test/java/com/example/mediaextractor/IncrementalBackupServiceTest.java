package com.example.mediaextractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalBackupServiceTest {

    private final IncrementalBackupService backupService = new IncrementalBackupService();

    @Test
    void testEmptyFilesListReturnsEmptyResult(@TempDir Path tempDir) throws Exception {
        Path memoriesDir = tempDir.resolve("memories");
        Path backupDir = tempDir.resolve("backups");
        Files.createDirectories(memoriesDir);

        IncrementalBackupService.BackupResult result = backupService.createIncrementalBackup(
                memoriesDir,
                List.of(),
                List.of(),
                backupDir
        );

        assertTrue(result.success());
        assertEquals(0, result.fileCount());
        assertTrue(result.createdVolumes().isEmpty());
        assertNull(result.receiptPath());
    }

    @Test
    void testIncrementalBackupCreationAndVerification(@TempDir Path tempDir) throws Exception {
        Path memoriesDir = tempDir.resolve("memories");
        Path backupDir = tempDir.resolve("backups");

        Path photoDir = memoriesDir.resolve("2024").resolve("photos");
        Path videoDir = memoriesDir.resolve("2024").resolve("videos");
        Files.createDirectories(photoDir);
        Files.createDirectories(videoDir);

        Path photo1 = photoDir.resolve("IMG_20240901_001.jpg");
        Path video1 = videoDir.resolve("MOV_20240901_001.mp4");
        Files.writeString(photo1, "photo data content 12345", StandardCharsets.UTF_8);
        Files.writeString(video1, "video data content 67890", StandardCharsets.UTF_8);

        Path reportJson = memoriesDir.resolve("media-extraction-report-20240901-120000.json");
        Path csvFile = memoriesDir.resolve("classification_results.csv");
        Files.writeString(reportJson, "{\"scanned\": 2}", StandardCharsets.UTF_8);
        Files.writeString(csvFile, "file_path,category\n", StandardCharsets.UTF_8);

        List<Path> newlyExtracted = List.of(photo1, video1);
        List<Path> metadataFiles = List.of(reportJson, csvFile);

        IncrementalBackupService.BackupResult result = backupService.createIncrementalBackup(
                memoriesDir,
                newlyExtracted,
                metadataFiles,
                backupDir,
                "10m",
                1,
                "/usr/bin/7z"
        );

        assertTrue(result.success());
        assertEquals(4, result.fileCount());
        assertFalse(result.createdVolumes().isEmpty());
        assertNotNull(result.receiptPath());
        assertTrue(Files.exists(result.receiptPath()));

        // Verify receipt content
        String receiptJson = Files.readString(result.receiptPath());
        assertTrue(receiptJson.contains("2024/photos/IMG_20240901_001.jpg"));
        assertTrue(receiptJson.contains("2024/videos/MOV_20240901_001.mp4"));
        assertTrue(receiptJson.contains("media-extraction-report-20240901-120000.json"));
        assertTrue(receiptJson.contains("classification_results.csv"));
        assertTrue(receiptJson.contains("\"filesPackaged\": 4"));

        // Verify all created volumes exist on disk
        for (IncrementalBackupService.BackupVolume vol : result.createdVolumes()) {
            Path volPath = backupDir.resolve(vol.fileName());
            assertTrue(Files.exists(volPath));
            assertTrue(vol.sizeBytes() > 0);
        }
    }

    @Test
    void testMultiPartVolumeSplitting(@TempDir Path tempDir) throws Exception {
        Path memoriesDir = tempDir.resolve("memories");
        Path backupDir = tempDir.resolve("backups");
        Path photoDir = memoriesDir.resolve("2024").resolve("photos");
        Files.createDirectories(photoDir);

        // Create a 2.5 MB file of dummy byte data
        byte[] dummyData = new byte[2_500_000];
        Arrays.fill(dummyData, (byte) 42);

        Path largeFile = photoDir.resolve("large_photo.raw");
        Files.write(largeFile, dummyData);

        // Split with part size 1m (-v1m) and compression store (-mx=0) to prevent compressing out the size
        IncrementalBackupService.BackupResult result = backupService.createIncrementalBackup(
                memoriesDir,
                List.of(largeFile),
                List.of(),
                backupDir,
                "1m",
                0,
                "/usr/bin/7z"
        );

        assertTrue(result.success());
        assertEquals(1, result.fileCount());
        // Should have created multiple volume parts (.001, .002, .003)
        assertTrue(result.createdVolumes().size() >= 2, "Expected multiple split parts for 2.5MB with 1MB part size");
        assertTrue(result.createdVolumes().get(0).fileName().endsWith(".001"));
        assertTrue(result.createdVolumes().get(1).fileName().endsWith(".002"));
    }

    @Test
    void testRelocationResolutionFromCsv(@TempDir Path tempDir) throws Exception {
        Path memoriesDir = tempDir.resolve("memories");
        Path backupDir = tempDir.resolve("backups");
        Path originalPhotoPath = memoriesDir.resolve("2024").resolve("photos").resolve("meme_extracted.jpg");
        Path relocatedPath = memoriesDir.resolve("quarantine").resolve("2024").resolve("memes").resolve("meme_extracted.jpg");

        Files.createDirectories(relocatedPath.getParent());
        Files.writeString(relocatedPath, "relocated meme content", StandardCharsets.UTF_8);

        // classification_results.csv mapping original to relocated
        // Header line:
        // file_path,category,confidence,tier,decision_reason,ocr_text,ocr_confidence,text_box_count,text_area_ratio,clip_top_label,clip_margin,greeting_keyword_hits,meme_keyword_hits,uncertainty,model_version,relocated_to,timestamp
        Path csvFile = memoriesDir.resolve("classification_results.csv");
        String header = "file_path,category,confidence,tier,decision_reason,ocr_text,ocr_confidence,text_box_count,text_area_ratio,clip_top_label,clip_margin,greeting_keyword_hits,meme_keyword_hits,uncertainty,model_version,relocated_to,timestamp\n";
        String row = "\"" + originalPhotoPath.toAbsolutePath().normalize() + "\",MEME,0.95,HIGH,text,none,0.0,0,0.0,meme,0.8,0,1,0.05,v1,\"" + relocatedPath.toAbsolutePath().normalize() + "\",2026-09-12 12:00:00\n";
        Files.writeString(csvFile, header + row, StandardCharsets.UTF_8);

        IncrementalBackupService.BackupResult result = backupService.createIncrementalBackup(
                memoriesDir,
                List.of(originalPhotoPath),
                List.of(),
                backupDir,
                "10m",
                1,
                "/usr/bin/7z"
        );

        assertTrue(result.success());
        assertEquals(1, result.fileCount());
        String receiptJson = Files.readString(result.receiptPath());
        assertTrue(receiptJson.contains("quarantine/2024/memes/meme_extracted.jpg"));
    }

    @Test
    void testFallbackQuarantineResolutionWithoutCsv(@TempDir Path tempDir) throws Exception {
        Path memoriesDir = tempDir.resolve("memories");
        Path backupDir = tempDir.resolve("backups");
        Path originalPhotoPath = memoriesDir.resolve("2024").resolve("photos").resolve("orphan_greeting.jpg");
        Path quarantineTarget = memoriesDir.resolve("quarantine").resolve("2024").resolve("greetings").resolve("orphan_greeting.jpg");

        Files.createDirectories(quarantineTarget.getParent());
        Files.writeString(quarantineTarget, "greeting quarantined content", StandardCharsets.UTF_8);

        IncrementalBackupService.BackupResult result = backupService.createIncrementalBackup(
                memoriesDir,
                List.of(originalPhotoPath),
                List.of(),
                backupDir,
                "10m",
                1,
                "/usr/bin/7z"
        );

        assertTrue(result.success());
        assertEquals(1, result.fileCount());
        String receiptJson = Files.readString(result.receiptPath());
        assertTrue(receiptJson.contains("quarantine/2024/greetings/orphan_greeting.jpg"));
    }

    @Test
    void testNonExistentFileGracefullySkipped(@TempDir Path tempDir) throws Exception {
        Path memoriesDir = tempDir.resolve("memories");
        Path backupDir = tempDir.resolve("backups");
        Path realPhoto = memoriesDir.resolve("2024").resolve("photos").resolve("real.jpg");
        Path phantomPhoto = memoriesDir.resolve("2024").resolve("photos").resolve("phantom.jpg");

        Files.createDirectories(realPhoto.getParent());
        Files.writeString(realPhoto, "real data", StandardCharsets.UTF_8);

        IncrementalBackupService.BackupResult result = backupService.createIncrementalBackup(
                memoriesDir,
                List.of(realPhoto, phantomPhoto),
                List.of(),
                backupDir,
                "10m",
                1,
                "/usr/bin/7z"
        );

        assertTrue(result.success());
        assertEquals(1, result.fileCount());
    }

    @Test
    void testCliArgumentParsingIncrementalFlags() {
        MediaExtractorApplication.ParsedArguments args = MediaExtractorApplication.parseArguments(
                "/source/dir",
                "--incremental",
                "--backup-dir=/external/backup",
                "--backup-part-size=2g",
                "--backup-compression=fast",
                "--backup-7z-binary=/opt/bin/7z"
        );

        assertTrue(args.incremental());
        assertEquals("/external/backup", args.backupDirArgument());
        assertEquals("2g", args.backupPartSize());
        assertEquals(1, args.backupCompression());
        assertEquals("/opt/bin/7z", args.backup7zBinary());
    }

    @Test
    void testCliArgumentParsingShortBackupFlags() {
        MediaExtractorApplication.ParsedArguments args = MediaExtractorApplication.parseArguments(
                "/source/dir",
                "-b", "/mnt/usb/backups",
                "--backup-compression=ultra"
        );

        assertTrue(args.incremental());
        assertEquals("/mnt/usb/backups", args.backupDirArgument());
        assertEquals(9, args.backupCompression());
    }
}

