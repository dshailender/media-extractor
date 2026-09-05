package com.example.mediaextractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void testDetectDuplicatesMultiTierExactSizeGrouping(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("unique1.jpg");
        Path file2 = tempDir.resolve("unique2.jpg");
        Path file3 = tempDir.resolve("unique3.jpg");

        // 3 files with completely different sizes
        Files.writeString(file1, "A");
        Files.writeString(file2, "BB");
        Files.writeString(file3, "CCC");

        var result = duplicateService.detectDuplicatesMultiTier(List.of(file1, file2, file3));
        assertEquals(0, result.duplicates().size(), "Files with unique sizes must not have any duplicates");
        assertEquals(3, result.validFiles().size());
        assertTrue(result.fullDigests().isEmpty(), "No full digests should be calculated for unique sizes (Zero-I/O)");
    }

    @Test
    void testDetectDuplicatesMultiTierSparseHashDifferentiates(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("same_size1.jpg");
        Path file2 = tempDir.resolve("same_size2.jpg");

        // Exact same length (10 chars), but differing content in sparse sample
        Files.writeString(file1, "0123456789");
        Files.writeString(file2, "abcdefghij");

        var result = duplicateService.detectDuplicatesMultiTier(List.of(file1, file2));
        assertEquals(0, result.duplicates().size());
        assertEquals(2, result.validFiles().size());
        assertTrue(result.fullDigests().isEmpty(), "Differing sparse hashes should not escalate to full digest");
    }

    @Test
    void testDetectDuplicatesMultiTierFullSha256Escalation(@TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("original.jpg");
        Path file2 = tempDir.resolve("duplicate.jpg");
        Path file3 = tempDir.resolve("another_unique.jpg");

        String identicalContent = "EXACT_DUPLICATE_CONTENT_12345";
        Files.writeString(file1, identicalContent);
        Files.writeString(file2, identicalContent);
        Files.writeString(file3, "DIFFERENT_LENGTH_CONTENT");

        var result = duplicateService.detectDuplicatesMultiTier(List.of(file1, file2, file3));
        assertEquals(1, result.duplicates().size(), "Exactly 1 duplicate file should be identified");
        assertTrue(result.duplicates().contains(file2) || result.duplicates().contains(file1));
        assertEquals(2, result.validFiles().size(), "Original + another unique must be valid");
        assertFalse(result.fullDigests().isEmpty(), "Escalated pair must have full digests recorded");
    }
}

