package com.example.mediaextractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MediaIntegrityServiceTest {

    private MediaIntegrityService integrityService;

    @BeforeEach
    void setUp() {
        MediaMetadataService metadataService = new MediaMetadataService();
        integrityService = new MediaIntegrityService(metadataService);
    }

    @Test
    void testValidJpegNotCorrupted(@TempDir Path tempDir) throws IOException {
        Path validJpeg = tempDir.resolve("valid.jpg");
        Files.write(validJpeg, createSimpleJpeg());

        assertFalse(integrityService.isCorrupted(validJpeg, "valid.jpg"));
    }

    @Test
    void testNonJpegExtensionCorrupted(@TempDir Path tempDir) throws IOException {
        Path fakeJpeg = tempDir.resolve("fake.jpg");
        Files.writeString(fakeJpeg, "INDX_DATABASE_INDEX_NOT_A_JPEG");

        assertTrue(integrityService.isCorrupted(fakeJpeg, "fake.jpg"));
    }

    @Test
    void testTruncatedJpegCorrupted(@TempDir Path tempDir) throws IOException {
        Path brokenJpeg = tempDir.resolve("broken.jpg");
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02, 0x03, 0x04};
        Files.write(brokenJpeg, bytes);

        assertTrue(integrityService.isCorrupted(brokenJpeg, "broken.jpg"));
    }

    @Test
    void testValidPngNotCorrupted(@TempDir Path tempDir) throws IOException {
        Path validPng = tempDir.resolve("valid.png");
        Files.write(validPng, createSimplePng());

        assertFalse(integrityService.isCorrupted(validPng, "valid.png"));
    }

    @Test
    void testBrokenPngCorrupted(@TempDir Path tempDir) throws IOException {
        Path brokenPng = tempDir.resolve("broken.png");
        byte[] bytes = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x01, 0x49, 0x48, 0x44, 0x52
        };
        Files.write(brokenPng, bytes);

        assertTrue(integrityService.isCorrupted(brokenPng, "broken.png"));
    }

    @Test
    void testCorruptBmpIdentified(@TempDir Path tempDir) throws IOException {
        Path brokenBmp = tempDir.resolve("broken.bmp");
        Files.writeString(brokenBmp, "OpenPGP Public Key Data");

        assertTrue(integrityService.isCorrupted(brokenBmp, "broken.bmp"));
    }

    @Test
    void testValidMp4NotCorrupted(@TempDir Path tempDir) throws IOException {
        Path validMp4 = tempDir.resolve("valid.mp4");
        // ftyp box (16 bytes) + moov box (8 bytes)
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.putInt(16);
        buffer.put("ftyp".getBytes(StandardCharsets.ISO_8859_1));
        buffer.put("isom".getBytes(StandardCharsets.ISO_8859_1));
        buffer.putInt(512);

        buffer.putInt(8);
        buffer.put("moov".getBytes(StandardCharsets.ISO_8859_1));

        Files.write(validMp4, buffer.array());
        assertFalse(integrityService.isCorrupted(validMp4, "valid.mp4"));
    }

    @Test
    void testMp4WithoutMoovCorrupted(@TempDir Path tempDir) throws IOException {
        Path brokenMp4 = tempDir.resolve("broken.mp4");
        // ftyp box (16 bytes) + mdat box (8 bytes) without moov
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.putInt(16);
        buffer.put("ftyp".getBytes(StandardCharsets.ISO_8859_1));
        buffer.put("isom".getBytes(StandardCharsets.ISO_8859_1));
        buffer.putInt(512);

        buffer.putInt(8);
        buffer.put("mdat".getBytes(StandardCharsets.ISO_8859_1));

        Files.write(brokenMp4, buffer.array());
        assertTrue(integrityService.isCorrupted(brokenMp4, "broken.mp4"));
    }

    @Test
    void testFakeMp4Corrupted(@TempDir Path tempDir) throws IOException {
        Path fakeMp4 = tempDir.resolve("fake.mp4");
        Files.writeString(fakeMp4, "RANDOM_GARBAGE_BYTES_FOR_VIDEO");

        assertTrue(integrityService.isCorrupted(fakeMp4, "fake.mp4"));
    }

    @Test
    void testQuarantineFileCopiesToQuarantineDirectory(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("corrupted.jpg");
        Files.write(source, new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00});

        Path baseMemoriesDir = tempDir.resolve("memories");
        Path quarantined = integrityService.quarantineFile(source, baseMemoriesDir, 2024, "photo", "missing EOI");

        assertNotNull(quarantined);
        assertTrue(Files.exists(quarantined));
        assertTrue(quarantined.toString().contains("quarantine"));
        assertTrue(quarantined.toString().contains("photos"));
    }

    private byte[] createSimpleJpeg() throws IOException {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    private byte[] createSimplePng() throws IOException {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}

