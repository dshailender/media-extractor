package com.example.mediaextractor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    void testYearExtractionPrefersExifDateForPhotos() throws IOException {
        Path testFile = tempRoot.resolve("exif-photo.jpg");
        byte[] jpegWithExif = createJpegWithExif("2022:06:15 10:30:00");
        Files.write(testFile, jpegWithExif);

        LocalDateTime dateTime2025 = LocalDateTime.of(2025, 1, 2, 3, 4, 5);
        long millis2025 = dateTime2025.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Files.setLastModifiedTime(testFile, FileTime.fromMillis(millis2025));

        int year = getYearFromFile(testFile);
        assertEquals(2022, year, "Exif DateTimeOriginal should take precedence over file modification time");
    }

    @Test
    void testDuplicateMediaFilesAreIgnored() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source");
        Files.createDirectories(sourceDir.resolve("sub1"));
        Files.createDirectories(sourceDir.resolve("sub2"));

        Path file1 = sourceDir.resolve("sub1").resolve("duplicate.jpg");
        Path file2 = sourceDir.resolve("sub2").resolve("duplicate.jpg");
        byte[] content = createSimpleJpeg();
        Files.write(file1, content);
        Files.write(file2, content);

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");
        try (var stream = Files.list(photosDir)) {
            var list = stream.toList();
            assertEquals(1, list.size(), "Duplicate content should be deduplicated to a single file");
            assertTrue(list.get(0).getFileName().toString().startsWith("IMG_"), "Result should have IMG_ prefix");
        }
    }

    @Test
    void testCorruptedPhotoIsSkipped() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source");
        Files.createDirectories(sourceDir);

        Path corruptPhoto = sourceDir.resolve("broken.jpg");
        Files.write(corruptPhoto, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x00, 0x00});

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");
        assertFalse(Files.exists(photosDir.resolve("broken.jpg")), "Corrupted media should not be copied");
    }

    @Test
    void testFlatDirectoryStructureCreation() throws IOException, InterruptedException {
        // Create source files with different years
        Path sourceDir = tempRoot.resolve("source");
        Files.createDirectories(sourceDir);

        Path photo2024 = sourceDir.resolve("photo2024.jpg");
        Path photo2023 = sourceDir.resolve("photo2023.jpg");
        Files.write(photo2024, createSimpleJpeg(0xFF0000));
        Files.write(photo2023, createSimpleJpeg(0x00FF00));

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
        try (var s = Files.list(photos2024)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("IMG_2024")), "Photo in 2024 should start with IMG_2024");
        }
        try (var s = Files.list(photos2023)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("IMG_2023")), "Photo in 2023 should start with IMG_2023");
        }

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
        Files.write(photo, createSimpleJpeg());
        Files.write(video, createSimpleMp4());

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");
        Path videosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("videos");

        try (var s = Files.list(photosDir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("IMG_")), "Photo should be in photos directory with IMG_");
        }
        try (var s = Files.list(videosDir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("MOV_")), "Video should be in videos directory with MOV_");
        }
    }

    @Test
    void testFilenameCollisionHandling() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source");
        Path sub1 = sourceDir.resolve("sub1");
        Path sub2 = sourceDir.resolve("sub2");
        Files.createDirectories(sub1);
        Files.createDirectories(sub2);

        // Create two files with same timestamp in different subdirectories
        Path file1 = sub1.resolve("same_photo.jpg");
        Path file2 = sub2.resolve("same_photo.jpg");
        Files.write(file1, createSimpleJpeg(0xFF0000));
        Files.write(file2, createSimpleJpeg(0x00FF00));
        setFileYear(file1, 2024);
        setFileYear(file2, 2024);

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        Path photosDir = baseMemoriesDir.resolve("2024").resolve("photos");

        // Verify both files exist with one having a collision suffix
        try (var stream = Files.list(photosDir)) {
            var list = stream.toList();
            assertEquals(2, list.size(), "Both files should be kept");
            assertTrue(list.stream().anyMatch(p -> p.getFileName().toString().startsWith("IMG_2024")), "Original filename should start with IMG_2024");
            assertTrue(list.stream().anyMatch(p -> p.getFileName().toString().contains("_1.jpg")), "Collision should create _1 suffix");
            byte[] content1 = Files.readAllBytes(list.get(0));
            byte[] content2 = Files.readAllBytes(list.get(1));
            assertFalse(Arrays.equals(content1, content2), "Files should have different content");
        }
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
            Files.write(file, createSimpleJpeg(i + 1, 2));
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

        Path innerArchive = tempRoot.resolve("inner.zip");
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(innerArchive))) {
            zipOut.putNextEntry(new ZipEntry("inner-photo.jpg"));
            zipOut.write(createSimpleJpeg());
            zipOut.closeEntry();
        }

        Path outerArchive = sourceDir.resolve("outer.zip");
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(outerArchive))) {
            zipOut.putNextEntry(new ZipEntry("archive/inner.zip"));
            zipOut.write(Files.readAllBytes(innerArchive));
            zipOut.closeEntry();
        }

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        if (!executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
            executor.shutdownNow();
            fail("Nested archive extraction did not finish within the timeout");
        }

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");
        assertTrue(Files.exists(photosDir), "Photos directory should exist");
        try (var stream = Files.list(photosDir)) {
            var files = stream.toList();
            assertEquals(1, files.size(), "Photo inside nested archive should be extracted");
            assertTrue(files.get(0).getFileName().toString().startsWith("IMG_"));
            assertTrue(files.get(0).getFileName().toString().endsWith(".jpg"));
        }
    }

    @Test
    void testSourceDirectoryHierarchyIgnored() throws IOException, InterruptedException {
        // Create deeply nested source structure
        Path sourceDir = tempRoot.resolve("source");
        Path deep = sourceDir.resolve("level1").resolve("level2").resolve("level3");
        Files.createDirectories(deep);

        Path photo = deep.resolve("nested_photo.jpg");
        Files.write(photo, createSimpleJpeg());

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");

        // Photo should be in flat photos directory, NOT in level1/level2/level3 hierarchy
        try (var stream = Files.list(photosDir)) {
            var files = stream.toList();
            assertEquals(1, files.size(), "Photo should be extracted to flat photos directory");
            assertTrue(files.get(0).getFileName().toString().startsWith("IMG_"));
            assertTrue(files.get(0).getFileName().toString().endsWith(".jpg"));
        }
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

        Files.write(photo2023, createSimpleJpeg(0x111111));
        Files.write(photo2024, createSimpleJpeg(0x222222));
        Files.write(video2023, createSimpleMp4(0));
        Files.write(video2024, createSimpleMp4(1));

        setFileYear(photo2023, 2023);
        setFileYear(photo2024, 2024);
        setFileYear(video2023, 2023);
        setFileYear(video2024, 2024);

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        // Verify all files are in correct year/type directories
        assertTrue(Files.exists(baseMemoriesDir.resolve("2023").resolve("photos").resolve("IMG_20230615_103000.jpg")));
        assertTrue(Files.exists(baseMemoriesDir.resolve("2024").resolve("photos").resolve("IMG_20240615_103000.jpg")));
        assertTrue(Files.exists(baseMemoriesDir.resolve("2023").resolve("videos").resolve("MOV_20230615_103000.mp4")));
        assertTrue(Files.exists(baseMemoriesDir.resolve("2024").resolve("videos").resolve("MOV_20240615_103000.mov")));
    }

    @Test
    void testGooglePhotosSidecarExtractionInPipeline() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source-sidecar");
        Files.createDirectories(sourceDir);

        Path photo = sourceDir.resolve("vacation.jpg");
        Files.write(photo, createSimpleJpeg());
        setFileYear(photo, 2026);

        // Google Photos JSON sidecar specifying year 2019 (epoch 1560600000 = June 15, 2019)
        Path sidecar = sourceDir.resolve("vacation.jpg.json");
        Files.writeString(sidecar, "{\"photoTakenTime\": {\"timestamp\": \"1560600000\"}}");

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        Path photos2019Dir = baseMemoriesDir.resolve("2019").resolve("photos");
        assertTrue(Files.exists(photos2019Dir), "2019 photos directory should exist");
        try (var stream = Files.list(photos2019Dir)) {
            var files = stream.toList();
            assertEquals(1, files.size(), "Photo with sidecar JSON must be organized under sidecar's year 2019");
            assertTrue(files.get(0).getFileName().toString().startsWith("IMG_2019"));
            assertTrue(files.get(0).getFileName().toString().endsWith(".jpg"));
        }
    }

    @Test
    void testFilenameRegexDateExtractionInPipeline() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source-filename");
        Files.createDirectories(sourceDir);

        Path photo = sourceDir.resolve("IMG_20200815_142030.jpg");
        Files.write(photo, createSimpleJpeg());
        setFileYear(photo, 2026);

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        Path photo2020 = baseMemoriesDir.resolve("2020").resolve("photos").resolve("IMG_20200815_142030.jpg");
        assertTrue(Files.exists(photo2020), "Photo with date in filename must be organized under year 2020");
    }

    @Test
    void testCorruptedPhotoIsQuarantinedInPipeline() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source-quarantine");
        Files.createDirectories(sourceDir);

        Path corruptPhoto = sourceDir.resolve("corrupted_photo.jpg");
        // Starts with JPEG SOI but missing valid structure
        Files.write(corruptPhoto, new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02, 0x03});

        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path quarantinePhoto = baseMemoriesDir.resolve("quarantine")
                .resolve(String.valueOf(currentYear))
                .resolve("photos")
                .resolve("corrupted_photo.jpg");

        assertTrue(Files.exists(quarantinePhoto), "Corrupted photo must be quarantined safely");
        ExtractionReport report = service.getLastReport();
        assertNotNull(report);
        assertEquals(1, report.corrupted());
        assertEquals(1, report.quarantined());
    }

    @Test
    void testIdempotentReRunProducesZeroDuplicates() throws IOException, InterruptedException {
        Path sourceDir = tempRoot.resolve("source-idempotent");
        Files.createDirectories(sourceDir);

        Path photo = sourceDir.resolve("landscape.jpg");
        Files.write(photo, createSimpleJpeg());

        // First run
        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        int currentYear = LocalDateTime.now().getYear();
        Path photosDir = baseMemoriesDir.resolve(String.valueOf(currentYear)).resolve("photos");
        try (var stream = Files.list(photosDir)) {
            var files = stream.toList();
            assertEquals(1, files.size(), "Target directory must contain exactly 1 copy");
            assertTrue(files.get(0).getFileName().toString().startsWith("IMG_"));
        }

        // Recreate executor for second run
        executor = Executors.newVirtualThreadPerTaskExecutor();
        service.setExecutor(executor);

        // Second run on the same source/destination
        service.extractMedia(sourceDir, baseMemoriesDir);
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // Verify no duplicate files were created
        try (var stream = Files.list(photosDir)) {
            var files = stream.toList();
            assertEquals(1, files.size(), "Target directory must still contain exactly 1 copy after re-run");
            assertTrue(files.get(0).getFileName().toString().startsWith("IMG_"));
        }
    }

    @Test
    void testSanitizeMemoriesRemovesCorruptAndCleansEmptyDirs() throws IOException {
        Path photos2002 = baseMemoriesDir.resolve("2002").resolve("photos");
        Files.createDirectories(photos2002);
        Path corrupt1 = photos2002.resolve("corrupt1.jpg");
        Path corrupt2 = photos2002.resolve("corrupt2.bmp");
        Files.writeString(corrupt1, "GARBAGE_CARVED_DATA");
        Files.writeString(corrupt2, "MORE_GARBAGE_DATA");

        Path photos2024 = baseMemoriesDir.resolve("2024").resolve("photos");
        Files.createDirectories(photos2024);
        Path validPhoto = photos2024.resolve("valid.jpg");
        Files.write(validPhoto, createSimpleJpeg());

        MediaExtractorService.SanitizationReport report = service.sanitizeMemories(baseMemoriesDir);

        assertEquals(3, report.scanned());
        assertEquals(1, report.valid());
        assertEquals(2, report.corrupted());
        assertEquals(0, report.duplicates());
        assertEquals(1, report.renamed());
        assertEquals(1, report.cleanedDirectories());

        assertFalse(Files.exists(photos2002));
        assertFalse(Files.exists(baseMemoriesDir.resolve("2002")));
        try (var stream = Files.list(photos2024)) {
            var files = stream.toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().startsWith("IMG_"));
        }
        assertTrue(Files.exists(baseMemoriesDir.resolve("quarantine").resolve("2002").resolve("photos").resolve("corrupt1.jpg")));
        assertTrue(Files.exists(baseMemoriesDir.resolve("quarantine").resolve("2002").resolve("photos").resolve("corrupt2.bmp")));
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

    private byte[] createJpegWithExif(String dateTimeOriginal) throws IOException {
        byte[] jpegBytes = createSimpleJpeg();
        byte[] exifPayload = buildExifPayload(dateTimeOriginal);

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(new byte[]{(byte) 0xFF, (byte) 0xD8});
        result.write(new byte[]{(byte) 0xFF, (byte) 0xE1});
        result.write(new byte[]{(byte) ((exifPayload.length + 2) >> 8), (byte) ((exifPayload.length + 2) & 0xFF)});
        result.write(exifPayload);
        result.write(Arrays.copyOfRange(jpegBytes, 2, jpegBytes.length));
        return result.toByteArray();
    }

    private byte[] createSimpleJpeg() throws IOException {
        return createSimpleJpeg(2, 2);
    }

    private byte[] createSimpleJpeg(int rgb) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        try (ByteArrayOutputStream original = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", original);
            return original.toByteArray();
        }
    }

    private byte[] createSimpleJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream original = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", original);
            return original.toByteArray();
        }
    }

    private byte[] createSimpleMp4() {
        return createSimpleMp4(0);
    }

    private byte[] createSimpleMp4(int variant) {
        ByteBuffer ftyp = ByteBuffer.allocate(16);
        ftyp.putInt(16);
        ftyp.put("ftyp".getBytes(StandardCharsets.ISO_8859_1));
        ftyp.put("isom".getBytes(StandardCharsets.ISO_8859_1));
        ftyp.putInt(512);

        ByteBuffer moov = ByteBuffer.allocate(8 + variant);
        moov.putInt(8 + variant);
        moov.put("moov".getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 0; i < variant; i++) {
            moov.put((byte) i);
        }

        ByteBuffer full = ByteBuffer.allocate(16 + 8 + variant);
        full.put(ftyp.array());
        full.put(moov.array());
        return full.array();
    }

    private byte[] buildExifPayload(String dateTimeOriginal) throws IOException {
        byte[] ascii = (dateTimeOriginal + "\0").getBytes(StandardCharsets.US_ASCII);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(new byte[]{0x45, 0x78, 0x69, 0x66, 0x00, 0x00});

        // TIFF header: little-endian, magic 42, IFD0 offset = 8
        payload.write(new byte[]{0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00});

        // IFD0: 1 entry (ExifIFD pointer)
        payload.write(new byte[]{0x01, 0x00});
        payload.write(new byte[]{0x69, (byte) 0x87, 0x04, 0x00});
        payload.write(new byte[]{0x01, 0x00, 0x00, 0x00});
        payload.write(new byte[]{0x1A, 0x00, 0x00, 0x00});
        payload.write(new byte[]{0x00, 0x00, 0x00, 0x00});

        // ExifIFD: 1 entry (DateTimeOriginal)
        payload.write(new byte[]{0x01, 0x00});
        payload.write(new byte[]{0x03, (byte) 0x90, 0x02, 0x00});
        payload.write(new byte[]{(byte) ascii.length, 0x00, 0x00, 0x00});
        payload.write(new byte[]{0x2C, 0x00, 0x00, 0x00});
        payload.write(new byte[]{0x00, 0x00, 0x00, 0x00});

        // Date string at offset 44 from TIFF start
        payload.write(ascii);
        return payload.toByteArray();
    }
}
