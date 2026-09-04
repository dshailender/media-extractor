package com.example.mediaextractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateDetectionServiceTest {

    private DuplicateDetectionService duplicateService;

    @BeforeEach
    void setUp() {
        duplicateService = new DuplicateDetectionService();
    }

    @Test
    void testPreIndexingIdentifiesExistingFilesAsDuplicates(@TempDir Path tempDir) throws IOException {
        Path memories = tempDir.resolve("memories");
        Path photosDir = memories.resolve("2024").resolve("photos");
        Files.createDirectories(photosDir);

        Path existingFile = photosDir.resolve("pic.jpg");
        Files.writeString(existingFile, "unique-photo-content-12345");

        // Index existing destination memories
        duplicateService.indexExistingDirectory(memories);

        // Candidate file with same content in source directory
        Path sourceDir = tempDir.resolve("source");
        Files.createDirectories(sourceDir);
        Path candidate = sourceDir.resolve("pic_duplicate.jpg");
        Files.writeString(candidate, "unique-photo-content-12345");

        assertTrue(duplicateService.isDuplicate(candidate), "Candidate matching pre-indexed file should be identified as duplicate");
    }

    @Test
    void testDifferentContentWithSameSizeNotDuplicate(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("file1.jpg");
        Path file2 = tempDir.resolve("file2.jpg");

        // Exact same length (10 chars), different content
        Files.writeString(file1, "ABCDEFGHIJ");
        Files.writeString(file2, "1234567890");

        String digest1 = duplicateService.computeFullDigest(file1);
        duplicateService.registerDigest(digest1, Files.size(file1), file1);

        assertFalse(duplicateService.isDuplicate(file2), "Same size but different content must NOT be flagged as duplicate");
    }

    @Test
    void testUniqueFileNameGeneration(@TempDir Path tempDir) throws IOException {
        Path targetDir = tempDir.resolve("photos");
        Files.createDirectories(targetDir);

        Path initial = targetDir.resolve("image.jpg");
        Files.writeString(initial, "init");

        Path unique1 = duplicateService.getUniqueFileName(targetDir, "image.jpg");
        assertEquals("image_1.jpg", unique1.getFileName().toString());
        Files.writeString(unique1, "unique1");

        Path unique2 = duplicateService.getUniqueFileName(targetDir, "image.jpg");
        assertEquals("image_2.jpg", unique2.getFileName().toString());
    }

    @Test
    void testTargetMatchesContent(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("target.jpg");
        Files.writeString(file1, "sample-content");

        String digest = duplicateService.computeFullDigest(file1);
        assertTrue(duplicateService.targetMatchesContent(file1, digest));

        Path file2 = tempDir.resolve("different.jpg");
        Files.writeString(file2, "other-content");
        assertFalse(duplicateService.targetMatchesContent(file2, digest));
    }
}

