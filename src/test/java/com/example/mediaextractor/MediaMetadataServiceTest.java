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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaMetadataServiceTest {

    private MediaMetadataService metadataService;

    @BeforeEach
    void setUp() {
        metadataService = new MediaMetadataService();
    }

    @Test
    void testGenerateTargetFileNameForPhoto() {
        LocalDateTime dt = LocalDateTime.of(2021, 8, 20, 15, 30, 0);
        Instant instant = dt.atZone(ZoneId.systemDefault()).toInstant();

        String fileName = metadataService.generateTargetFileName("photo", instant, "[013178]_1.JPG");
        assertEquals("IMG_20210820_153000.jpg", fileName);
    }

    @Test
    void testGenerateTargetFileNameForVideo() {
        LocalDateTime dt = LocalDateTime.of(2022, 5, 10, 12, 45, 30);
        Instant instant = dt.atZone(ZoneId.systemDefault()).toInstant();

        String fileName = metadataService.generateTargetFileName("video", instant, "movie_clip.MOV");
        assertEquals("MOV_20220510_124530.mov", fileName);
    }

    @Test
    void testGenerateTargetFileNameCustomPattern() {
        metadataService.setDateFormatPattern("ddMMyyyy_HHmmss");
        LocalDateTime dt = LocalDateTime.of(2021, 8, 20, 15, 30, 0);
        Instant instant = dt.atZone(ZoneId.systemDefault()).toInstant();

        String fileName = metadataService.generateTargetFileName("photo", instant, "photo.PNG");
        assertEquals("IMG_20082021_153000.png", fileName);
    }

    @Test
    void testExtractExifYearFromJpeg(@TempDir Path tempDir) throws IOException {
        Path photo = tempDir.resolve("sample.jpg");
        byte[] jpegWithExif = createJpegWithExif("2021:08:20 15:30:00");
        Files.write(photo, jpegWithExif);

        // Set filesystem time to 2025 to verify EXIF takes precedence
        setFileYear(photo, 2025);

        MediaMetadataService.Metadata meta = metadataService.extractMetadata(photo);
        assertNotNull(meta);
        assertEquals(2021, meta.year());
        assertEquals("exif", meta.source());
    }

    @Test
    void testExtractVideoMetadataFromMvhdAtom(@TempDir Path tempDir) throws IOException {
        Path video = tempDir.resolve("sample.mp4");
        // Create an MP4 with mvhd timestamp for 2022-05-10
        byte[] mp4Bytes = createMinimalMp4WithMvhd(2022);
        Files.write(video, mp4Bytes);

        // Set filesystem time to 2026 to verify video atom takes precedence
        setFileYear(video, 2026);

        MediaMetadataService.Metadata meta = metadataService.extractMetadata(video);
        assertNotNull(meta);
        assertEquals(2022, meta.year());
        assertEquals("video-mvhd", meta.source());
    }

    @Test
    void testExtractSidecarJsonMetadata(@TempDir Path tempDir) throws IOException {
        Path photo = tempDir.resolve("IMG_0042.jpg");
        Files.write(photo, "plain-image-content".getBytes(StandardCharsets.UTF_8));
        setFileYear(photo, 2026);

        // Google Photos Takeout format
        Path sidecar = tempDir.resolve("IMG_0042.jpg.json");
        // Epoch 1560600000 = June 15, 2019
        String jsonContent = "{\n  \"title\": \"IMG_0042.jpg\",\n  \"photoTakenTime\": {\n    \"timestamp\": \"1560600000\"\n  }\n}";
        Files.writeString(sidecar, jsonContent);

        MediaMetadataService.Metadata meta = metadataService.extractMetadata(photo);
        assertNotNull(meta);
        assertEquals(2019, meta.year());
        assertEquals("sidecar-json", meta.source());
    }

    @Test
    void testExtractDateFromFilenameRegex(@TempDir Path tempDir) throws IOException {
        Path photo = tempDir.resolve("IMG_20231104_123045.jpg");
        Files.write(photo, "plain-image-content".getBytes(StandardCharsets.UTF_8));
        setFileYear(photo, 2026);

        MediaMetadataService.Metadata meta = metadataService.extractMetadata(photo);
        assertNotNull(meta);
        assertEquals(2023, meta.year());
        assertEquals("filename-regex", meta.source());
    }

    @Test
    void testIsAlreadyFormatted() {
        assertTrue(metadataService.isAlreadyFormatted("IMG_20231104_123045.jpg"));
        assertTrue(metadataService.isAlreadyFormatted("IMG_20231104_123045_1.jpg"));
        assertTrue(metadataService.isAlreadyFormatted("MOV_20220510_124530.mov"));
        assertTrue(metadataService.isAlreadyFormatted("MOV_20220510_124530_3.mp4"));

        assertFalse(metadataService.isAlreadyFormatted("photo.jpg"));
        assertFalse(metadataService.isAlreadyFormatted("IMG_0042.jpg"));
        assertFalse(metadataService.isAlreadyFormatted("sample.png"));
        assertFalse(metadataService.isAlreadyFormatted("IMG_abcd.jpg"));
        assertFalse(metadataService.isAlreadyFormatted(null));
        assertFalse(metadataService.isAlreadyFormatted(""));
    }

    @Test
    void testFastPathSkipsExifParsingForAlreadyFormattedFile(@TempDir Path tempDir) throws IOException {
        Path photo = tempDir.resolve("IMG_20240510_123000.jpg");
        // Create JPEG with EXIF date in 2018
        byte[] jpegWithExif = createJpegWithExif("2018:01:01 00:00:00");
        Files.write(photo, jpegWithExif);

        // Fast-path should extract 2024 from filename directly, bypassing 2018 EXIF
        MediaMetadataService.Metadata meta = metadataService.extractMetadata(photo);
        assertNotNull(meta);
        assertEquals(2024, meta.year());
        assertEquals("filename-regex", meta.source());

        // For non-formatted filename with the same EXIF bytes, EXIF is parsed and yields 2018
        Path unformatted = tempDir.resolve("camera_photo.jpg");
        Files.write(unformatted, jpegWithExif);
        MediaMetadataService.Metadata unformattedMeta = metadataService.extractMetadata(unformatted);
        assertNotNull(unformattedMeta);
        assertEquals(2018, unformattedMeta.year());
        assertEquals("exif", unformattedMeta.source());
    }

    @Test
    void testFallbackToFilesystemDate(@TempDir Path tempDir) throws IOException {
        Path photo = tempDir.resolve("unlabeled.jpg");
        Files.write(photo, "plain-image-content".getBytes(StandardCharsets.UTF_8));
        setFileYear(photo, 2017);

        MediaMetadataService.Metadata meta = metadataService.extractMetadata(photo);
        assertNotNull(meta);
        assertEquals(2017, meta.year());
        assertEquals("filesystem", meta.source());
    }

    private void setFileYear(Path file, int year) throws IOException {
        LocalDateTime dt = LocalDateTime.of(year, 6, 15, 12, 0, 0);
        long millis = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
    }

    private byte[] createMinimalMp4WithMvhd(int year) throws IOException {
        // Seconds from 1904-01-01 to 1970-01-01 is 2082844800L
        LocalDateTime dt = LocalDateTime.of(year, 5, 10, 12, 0, 0);
        long unixSeconds = dt.atZone(ZoneId.of("UTC")).toInstant().getEpochSecond();
        long mp4Seconds = unixSeconds + 2082844800L;

        // ftyp box (16 bytes)
        ByteBuffer ftyp = ByteBuffer.allocate(16);
        ftyp.putInt(16);
        ftyp.put("ftyp".getBytes(StandardCharsets.ISO_8859_1));
        ftyp.put("isom".getBytes(StandardCharsets.ISO_8859_1));
        ftyp.putInt(512);

        // mvhd payload (version 0): size(4), 'mvhd'(4), ver/flags(4), creation(4), mod(4), timescale(4), duration(4) = 28 bytes
        ByteBuffer mvhd = ByteBuffer.allocate(28);
        mvhd.putInt(28);
        mvhd.put("mvhd".getBytes(StandardCharsets.ISO_8859_1));
        mvhd.putInt(0); // version 0, flags 0
        mvhd.putInt((int) mp4Seconds);
        mvhd.putInt((int) mp4Seconds);
        mvhd.putInt(1000);
        mvhd.putInt(10000);

        // moov box enclosing mvhd: size = 8 + 28 = 36 bytes
        ByteBuffer moov = ByteBuffer.allocate(36);
        moov.putInt(36);
        moov.put("moov".getBytes(StandardCharsets.ISO_8859_1));
        moov.put(mvhd.array());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ftyp.array());
        out.write(moov.array());
        return out.toByteArray();
    }

    private byte[] createJpegWithExif(String dateTimeOriginal) throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", original);
        byte[] jpegBytes = original.toByteArray();

        byte[] ascii = (dateTimeOriginal + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(new byte[]{0x45, 0x78, 0x69, 0x66, 0x00, 0x00});
        payload.write(new byte[]{0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00}); // TIFF
        payload.write(new byte[]{0x01, 0x00}); // 1 entry in IFD0
        payload.write(new byte[]{0x69, (byte) 0x87, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1A, 0x00, 0x00, 0x00}); // Exif IFD at offset 26
        payload.write(new byte[]{0x00, 0x00, 0x00, 0x00});
        payload.write(new byte[]{0x01, 0x00}); // Exif IFD: 1 entry
        payload.write(new byte[]{0x03, (byte) 0x90, 0x02, 0x00}); // DateTimeOriginal
        payload.write(new byte[]{(byte) ascii.length, 0x00, 0x00, 0x00});
        payload.write(new byte[]{0x2C, 0x00, 0x00, 0x00}); // offset 44
        payload.write(new byte[]{0x00, 0x00, 0x00, 0x00});
        payload.write(ascii);
        byte[] exifPayload = payload.toByteArray();

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(new byte[]{(byte) 0xFF, (byte) 0xD8});
        result.write(new byte[]{(byte) 0xFF, (byte) 0xE1});
        result.write(new byte[]{(byte) ((exifPayload.length + 2) >> 8), (byte) ((exifPayload.length + 2) & 0xFF)});
        result.write(exifPayload);
        result.write(Arrays.copyOfRange(jpegBytes, 2, jpegBytes.length));
        return result.toByteArray();
    }
}

