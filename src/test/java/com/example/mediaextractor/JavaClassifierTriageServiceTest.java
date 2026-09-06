package com.example.mediaextractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClassifierTriageServiceTest {

    private MediaMetadataService metadataService;
    private JavaClassifierTriageService triageService;

    @BeforeEach
    void setUp() {
        metadataService = new MediaMetadataService();
        triageService = new JavaClassifierTriageService(metadataService);
    }

    @Test
    void testTriageNonImageReturnsUnsupported(@TempDir Path tempDir) throws IOException {
        Path textFile = tempDir.resolve("document.txt");
        Files.writeString(textFile, "not an image");

        JavaClassifierTriageService.TriageDecision decision = triageService.triage(textFile, 2024);
        assertNotNull(decision);
        assertEquals(JavaClassifierTriageService.TriageCategory.NEEDS_PYTHON, decision.category());
    }

    @Test
    void testTriagePngNeedsPython(@TempDir Path tempDir) throws IOException {
        Path pngFile = tempDir.resolve("sample.png");
        BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(img, "png", pngFile.toFile());

        JavaClassifierTriageService.TriageDecision decision = triageService.triage(pngFile, 2024);
        assertNotNull(decision);
        assertEquals(JavaClassifierTriageService.TriageCategory.NEEDS_PYTHON, decision.category());
        assertTrue(decision.reason().contains("Non-camera container format"));
    }

    @Test
    void testTriageScreenshotFilenameNeedsPython(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("Screenshot_20240815.jpg");
        BufferedImage img = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "jpg", file.toFile());

        JavaClassifierTriageService.TriageDecision decision = triageService.triage(file, 2024);
        assertNotNull(decision);
        assertEquals(JavaClassifierTriageService.TriageCategory.NEEDS_PYTHON, decision.category());
        assertTrue(decision.reason().contains("screenshot"));
    }

    @Test
    void testTriageSmallDimensionsNeedsPython(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("tiny.jpg");
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "jpg", file.toFile());

        JavaClassifierTriageService.TriageDecision decision = triageService.triage(file, 2024);
        assertNotNull(decision);
        assertEquals(JavaClassifierTriageService.TriageCategory.NEEDS_PYTHON, decision.category());
        assertTrue(decision.reason().contains("too small"));
    }

    @Test
    void testTriageScreenshotAspectRatioNeedsPython(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("portrait_screen.jpg");
        // 1080 x 2400 has AR = 0.45 (typical phone screenshot)
        BufferedImage img = new BufferedImage(1080, 2400, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "jpg", file.toFile());

        JavaClassifierTriageService.TriageDecision decision = triageService.triage(file, 2024);
        assertNotNull(decision);
        assertEquals(JavaClassifierTriageService.TriageCategory.NEEDS_PYTHON, decision.category());
        assertTrue(decision.reason().contains("screenshot aspect ratio"));
    }

    @Test
    void testTriageNoCameraExifNeedsPython(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("normal_photo_no_exif.jpg");
        BufferedImage img = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "jpg", file.toFile());

        JavaClassifierTriageService.TriageDecision decision = triageService.triage(file, 2024);
        assertNotNull(decision);
        assertEquals(JavaClassifierTriageService.TriageCategory.NEEDS_PYTHON, decision.category());
        assertTrue(decision.reason().contains("Missing camera hardware EXIF"));
    }

    @Test
    void testTriageCertifiedCameraPhoto(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("camera_photo.jpg");
        byte[] jpegBytes = createJpegWithExifCameraTags(1600, 1200, "Google", "Pixel 7", null);
        Files.write(file, jpegBytes);

        JavaClassifierTriageService.TriageDecision decision = triageService.triage(file, 2024);
        assertNotNull(decision);
        assertEquals(JavaClassifierTriageService.TriageCategory.PHOTO_CERTAIN, decision.category());
        assertTrue(decision.reason().contains("Certified camera photo"));
        assertTrue(decision.reason().contains("Pixel 7"));
    }

    @Test
    void testTriageEditorSoftwareNeedsPython(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("photo_with_editor.jpg");
        byte[] jpegBytes = createJpegWithExifCameraTags(1600, 1200, "Apple", "iPhone 14", "Adobe Photoshop 2024");
        Files.write(file, jpegBytes);

        JavaClassifierTriageService.TriageDecision decision = triageService.triage(file, 2024);
        assertNotNull(decision);
        assertEquals(JavaClassifierTriageService.TriageCategory.NEEDS_PYTHON, decision.category());
        assertTrue(decision.reason().contains("Image edited by software"), "Actual reason was: " + decision.reason());
    }

    @Test
    void testTriageAndStageWorkflow(@TempDir Path tempDir) throws IOException {
        Path baseMemories = tempDir.resolve("memories");
        Path photos2024 = baseMemories.resolve("2024").resolve("photos");
        Files.createDirectories(photos2024);

        // 1. Certified camera photo
        Path cameraPhoto = photos2024.resolve("IMG_20240101_001.jpg");
        Files.write(cameraPhoto, createJpegWithExifCameraTags(1600, 1200, "Canon", "EOS R6", null));

        // 2. Candidate meme image (PNG)
        Path memeCandidate = photos2024.resolve("meme_stick.png");
        BufferedImage memeImg = new BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(memeImg, "png", memeCandidate.toFile());

        List<Path> imagePaths = List.of(cameraPhoto, memeCandidate);

        JavaClassifierTriageService.TriageSummary summary = triageService.triageAndStage(
                imagePaths, baseMemories, "move", 4
        );

        assertNotNull(summary);
        assertEquals(2, summary.totalInspected());
        assertEquals(1, summary.certifiedPhotos());
        assertEquals(1, summary.stagedForPython());
        assertNotNull(summary.stagingDir());
        assertTrue(Files.exists(summary.stagingDir()));
        assertNotNull(summary.manifestPath());
        assertTrue(Files.exists(summary.manifestPath()));

        // Camera photo remains in place
        assertTrue(Files.exists(cameraPhoto));

        // Meme candidate moved to staging
        assertFalse(Files.exists(memeCandidate));

        // Read manifest
        List<String> manifestLines = Files.readAllLines(summary.manifestPath(), StandardCharsets.UTF_8);
        assertEquals(1, manifestLines.size());
        assertTrue(manifestLines.get(0).contains("meme_stick.png"));
        assertTrue(manifestLines.get(0).contains("NEEDS_PYTHON"));

        // Test restoreStrandedFiles safety rollback
        int restored = triageService.restoreStrandedFiles(summary);
        assertEquals(1, restored);
        assertTrue(Files.exists(memeCandidate), "Stranded candidate file must be restored to original path");

        // Test cleanup
        triageService.cleanupStagingDir(summary);
        assertFalse(Files.exists(summary.stagingDir()), "Staging dir should be deleted after cleanup");
    }

    private byte[] createJpegWithExifCameraTags(int width, int height, String make, String model, String software) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", original);
        byte[] jpegBytes = original.toByteArray();

        byte[] makeBytes = (make + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] modelBytes = (model + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] softwareBytes = software != null ? (software + "\0").getBytes(StandardCharsets.US_ASCII) : null;

        int entryCount = softwareBytes != null ? 3 : 2;
        int ifdSize = 2 + (entryCount * 12) + 4;
        int dataOffset = 8 + ifdSize; // offset from TIFF header to string data

        int makeOffset = dataOffset;
        int modelOffset = makeOffset + makeBytes.length;
        int softwareOffset = modelOffset + modelBytes.length;

        ByteArrayOutputStream tiffStream = new ByteArrayOutputStream();
        // TIFF header: "II", magic 42, offset to IFD0 = 8
        tiffStream.write(new byte[]{0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00});

        // IFD0: entry count
        tiffStream.write(new byte[]{(byte) entryCount, 0x00});

        // Entry 1: Make (0x010F), Type 2 (ASCII)
        tiffStream.write(new byte[]{0x0F, 0x01, 0x02, 0x00});
        tiffStream.write(new byte[]{(byte) makeBytes.length, 0x00, 0x00, 0x00});
        tiffStream.write(new byte[]{(byte) (makeOffset & 0xFF), (byte) ((makeOffset >> 8) & 0xFF), 0x00, 0x00});

        // Entry 2: Model (0x0110), Type 2 (ASCII)
        tiffStream.write(new byte[]{0x10, 0x01, 0x02, 0x00});
        tiffStream.write(new byte[]{(byte) modelBytes.length, 0x00, 0x00, 0x00});
        tiffStream.write(new byte[]{(byte) (modelOffset & 0xFF), (byte) ((modelOffset >> 8) & 0xFF), 0x00, 0x00});

        // Entry 3: Software (0x0131), Type 2 (ASCII) if present
        if (softwareBytes != null) {
            tiffStream.write(new byte[]{0x31, 0x01, 0x02, 0x00});
            tiffStream.write(new byte[]{(byte) softwareBytes.length, 0x00, 0x00, 0x00});
            tiffStream.write(new byte[]{(byte) (softwareOffset & 0xFF), (byte) ((softwareOffset >> 8) & 0xFF), 0x00, 0x00});
        }

        // Next IFD offset: 0
        tiffStream.write(new byte[]{0x00, 0x00, 0x00, 0x00});

        // String payloads
        tiffStream.write(makeBytes);
        tiffStream.write(modelBytes);
        if (softwareBytes != null) {
            tiffStream.write(softwareBytes);
        }

        byte[] tiffBytes = tiffStream.toByteArray();
        ByteArrayOutputStream exifPayload = new ByteArrayOutputStream();
        exifPayload.write(new byte[]{0x45, 0x78, 0x69, 0x66, 0x00, 0x00});
        exifPayload.write(tiffBytes);
        byte[] exifBytes = exifPayload.toByteArray();

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(new byte[]{(byte) 0xFF, (byte) 0xD8});
        result.write(new byte[]{(byte) 0xFF, (byte) 0xE1});
        result.write(new byte[]{(byte) ((exifBytes.length + 2) >> 8), (byte) ((exifBytes.length + 2) & 0xFF)});
        result.write(exifBytes);
        result.write(Arrays.copyOfRange(jpegBytes, 2, jpegBytes.length));
        return result.toByteArray();
    }
}
