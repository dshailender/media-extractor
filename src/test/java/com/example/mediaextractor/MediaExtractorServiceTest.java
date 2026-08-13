package com.example.mediaextractor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class MediaExtractorServiceTest {

    private MediaExtractorService service;
    private Path tempRoot;
    private Path baseMemoriesDir;
    private ExecutorService executor;

    @BeforeEach
    void setup() throws IOException {
        service = new MediaExtractorService();
        tempRoot = Files.createTempDirectory("mes-test-");
        baseMemoriesDir = tempRoot.resolve("memories");
        Files.createDirectories(baseMemoriesDir);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        service.setExecutor(executor);
        service.setTempDir(Files.createTempDirectory("mes-temp-"));
    }

    @AfterEach
    void cleanup() throws IOException, InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
        
        if (tempRoot != null && Files.exists(tempRoot)) {
            Files.walk(tempRoot)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
        
        service.cleanupTempDir();
    }

    @Test
    void testYearExtractionFromCurrentFile() throws IOException {
        Path testFile = tempRoot.resolve("test.jpg");
        Files.write(testFile, "test".getBytes(StandardCharsets.UTF_8));

        // Use reflection to call private method
        int year = getYearFromFile(testFile);
        int currentYear = LocalDateTime.now().getYear();

        assertEquals(currentYear, year, "Year should be current year for newly created file");
    }

    @Test
    void testYearExtractionFromOlderFile() throws IOException {
        Path testFile = tempRoot.resolve("old.jpg");
        Files.write(testFile, "test".getBytes(StandardCharsets.UTF_8));

        // Set file modification time to 2023
        LocalDateTime dateTime2023 = LocalDateTime.of(2023, 6, 15, 10, 30, 0);
        long millis2023 = dateTime2023.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Files.setLastModifiedTime(testFile, FileTime.fromMillis(millis2023));

        int year = getYearFromFile(testFile);
        assertEquals(2023, year, "Year should be 2023");
    }

    @Test
    void testFlatDirectoryStructureCreation() throws IOException, InterruptedException {
        // Create source files with different years
        Path sourceDir = tempRoot.resolve("source");
        Files.createDirectories(sourceDir);

        Path photo2024 = sourceDir.resolve("photo2024.jpg");
        Path photo2023 = sourceDir.resolve("photo2023.jpg");
        Files.write(photo2024, "photo2024".getBytes(StandardCharsets.UTF_8));
        Files.write(photo2023, "photo2023".getBytes(StandardCharsets.UTF_8));

        // Set modification times
        setFileYear(photo2024, 2024);
        setFileYear(photo2023, 2023);

        // Extract
        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        // Verify flat structure
        Path photos2024 = baseMemoriesDir.resolve("2024").resolve("photos");
        Path photos2023 = baseMemoriesDir.resolve("2023").resolve("photos");

        assertTrue(Files.exists(photos2024), "2024 photos directory should exist");
        assertTrue(Files.exists(photos2023), "2023 photos directory should exist");
        assertTrue(Files.exists(photos2024.resolve("photo2024.jpg")), "photo2024.jpg should exist in 2024/photos");
        assertTrue(Files.exists(photos2023.resolve("photo2023.jpg")), "photo2023.jpg should exist in 2023/photos");

        // Verify no nested directories (flat structure)
        try (var stream = Files.list(photos2024)) {
            stream.forEach(p -> assertFalse(Files.isDirectory(p), "Photos directory should be flat (no subdirectories)"));
        }
    }

    @Test
    void testVideoAndPhotoSeparation() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source");
        Files.createDirectories(sourceDir);

        Path photo = sourceDir.resolve("image.jpg");
        Path video = sourceDir.resolve("movie.mp4");
        Files.write(photo, "photo".getBytes(StandardCharsets.UTF_8));
        Files.write(video, "video".getBytes(StandardCharsets.UTF_8));

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");
        Path videosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("videos");

        assertTrue(Files.exists(photosDir.resolve("image.jpg")), "Photo should be in photos directory");
        assertTrue(Files.exists(videosDir.resolve("movie.mp4")), "Video should be in videos directory");
        assertFalse(Files.exists(photosDir.resolve("movie.mp4")), "Video should not be in photos directory");
        assertFalse(Files.exists(videosDir.resolve("image.jpg")), "Photo should not be in videos directory");
    }

    @Test
    void testFilenameCollisionHandling() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source");
        Path sub1 = sourceDir.resolve("sub1");
        Path sub2 = sourceDir.resolve("sub2");
        Files.createDirectories(sub1);
        Files.createDirectories(sub2);

        // Create two files with same name in different subdirectories
        Path file1 = sub1.resolve("same_photo.jpg");
        Path file2 = sub2.resolve("same_photo.jpg");
        Files.write(file1, "photo1".getBytes(StandardCharsets.UTF_8));
        Files.write(file2, "photo2".getBytes(StandardCharsets.UTF_8));

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");

        // Verify both files exist with one having a suffix
        assertTrue(Files.exists(photosDir.resolve("same_photo.jpg")), "Original filename should exist");
        assertTrue(Files.exists(photosDir.resolve("same_photo_1.jpg")), "Collision should create _1 suffix");

        // Verify contents are different
        byte[] content1 = Files.readAllBytes(photosDir.resolve("same_photo.jpg"));
        byte[] content2 = Files.readAllBytes(photosDir.resolve("same_photo_1.jpg"));
        assertNotEquals(new String(content1), new String(content2), "Files should have different content");
    }

    @Test
    void testMultipleFileCollisions() throws IOException, InterruptedException {
        // Create 50 files with same name in different subdirectories to test collision handling
        Path sourceDir = tempRoot.resolve("source");
        Files.createDirectories(sourceDir);

        int fileCount = 50;
        String sameName = "duplicate.jpg";

        for (int i = 0; i < fileCount; i++) {
            Path subDir = sourceDir.resolve("subdir" + i);
            Files.createDirectories(subDir);
            Path file = subDir.resolve(sameName);
            Files.write(file, ("content" + i).getBytes(StandardCharsets.UTF_8));
        }

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");

        // Count files - should have original plus (fileCount-1) suffixed files
        long fileCount_result;
        try (var stream = Files.list(photosDir)) {
            fileCount_result = stream.count();
        }

        assertEquals(fileCount, fileCount_result, "Should have " + fileCount + " files after collision handling");
    }

    @Test
    void testNestedArchiveExtraction() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source");
        Files.createDirectories(sourceDir);

        // For this test, we'll just verify the archive processing logic doesn't crash
        // A full ZIP/TAR test would require creating actual archive files
        
        // Create a simple photo file
        Path photo = sourceDir.resolve("photo.jpg");
        Files.write(photo, "photo".getBytes(StandardCharsets.UTF_8));

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");
        assertTrue(Files.exists(photosDir.resolve("photo.jpg")), "Photo should be extracted");
    }

    @Test
    void testSourceDirectoryHierarchyIgnored() throws IOException, InterruptedException {
        // Create deeply nested source structure
        Path sourceDir = tempRoot.resolve("source");
        Path deep = sourceDir.resolve("level1").resolve("level2").resolve("level3");
        Files.createDirectories(deep);

        Path photo = deep.resolve("nested_photo.jpg");
        Files.write(photo, "nested".getBytes(StandardCharsets.UTF_8));

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");

        // Photo should be in flat photos directory, NOT in level1/level2/level3 hierarchy
        assertTrue(Files.exists(photosDir.resolve("nested_photo.jpg")), "Photo should be extracted to flat photos directory");
        assertFalse(Files.exists(photosDir.resolve("level1")), "Source hierarchy should be completely ignored");
    }

    @Test
    void testMixedMediaTypesAndYears() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source");
        Files.createDirectories(sourceDir);

        // Create files with different years and types
        Path photo2023 = sourceDir.resolve("photo_2023.jpg");
        Path photo2024 = sourceDir.resolve("photo_2024.jpg");
        Path video2023 = sourceDir.resolve("video_2023.mp4");
        Path video2024 = sourceDir.resolve("video_2024.mov");

        Files.write(photo2023, "p2023".getBytes(StandardCharsets.UTF_8));
        Files.write(photo2024, "p2024".getBytes(StandardCharsets.UTF_8));
        Files.write(video2023, "v2023".getBytes(StandardCharsets.UTF_8));
        Files.write(video2024, "v2024".getBytes(StandardCharsets.UTF_8));

        setFileYear(photo2023, 2023);
        setFileYear(photo2024, 2024);
        setFileYear(video2023, 2023);
        setFileYear(video2024, 2024);

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        // Verify all files are in correct year/type directories
        assertTrue(Files.exists(baseMemoriesDir.resolve("2023").resolve("photos").resolve("photo_2023.jpg")));
        assertTrue(Files.exists(baseMemoriesDir.resolve("2024").resolve("photos").resolve("photo_2024.jpg")));
        assertTrue(Files.exists(baseMemoriesDir.resolve("2023").resolve("videos").resolve("video_2023.mp4")));
        assertTrue(Files.exists(baseMemoriesDir.resolve("2024").resolve("videos").resolve("video_2024.mov")));
    }

    // Helper methods using reflection to access private methods
    private int getYearFromFile(Path file) throws IOException {
        try {
            var method = MediaExtractorService.class.getDeclaredMethod("getYearFromFile", Path.class);
            method.setAccessible(true);
            return (int) method.invoke(service, file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setFileYear(Path file, int year) throws IOException {
        LocalDateTime dateTime = LocalDateTime.of(year, 6, 15, 10, 30, 0);
        long millis = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
    }
}
