package com.example.mediaextractor;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

@Service
public class MediaIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(MediaIntegrityService.class);

    private static final Set<String> HEIC_BRANDS = Set.of(
            "heic", "heix", "hevc", "heim", "heis", "mif1", "msf1", "avif", "avis"
    );

    private static final Set<String> MP4_FIRST_BOXES = Set.of(
            "ftyp", "moov", "mdat", "wide", "free", "skip"
    );

    private static final byte[] WMV_GUID = new byte[]{
            0x30, 0x26, (byte) 0xB2, 0x75, (byte) 0x8E, 0x66, (byte) 0xCF, 0x11,
            (byte) 0xA6, (byte) 0xD9, 0x00, (byte) 0xAA, 0x00, 0x62, (byte) 0xCE, 0x6C
    };

    private final MediaMetadataService metadataService;

    public MediaIntegrityService(MediaMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    public boolean isCorrupted(Path file, String fileName) {
        try {
            if (!Files.exists(file)) {
                return true;
            }
            long size = Files.size(file);
            if (size <= 0) {
                return true;
            }

            if (metadataService.isPhoto(fileName)) {
                return isPhotoCorrupted(file, fileName, size);
            }

            if (metadataService.isVideo(fileName)) {
                return isVideoCorrupted(file, fileName, size);
            }

            if (metadataService.isArchiveFile(fileName)) {
                return isArchiveCorrupted(file, fileName, size);
            }

            return false;
        } catch (IOException e) {
            log.warn("File {} could not be read for corruption check: {}", file, e.getMessage());
            return true;
        }
    }

    private boolean isPhotoCorrupted(Path file, String fileName, long size) throws IOException {
        String ext = getExtension(fileName);
        if (ext == null) {
            return true;
        }

        byte[] headBytes = readHeaderBytes(file, 32);
        if (headBytes == null || headBytes.length < 2) {
            return true;
        }

        switch (ext) {
            case "jpg", "jpeg" -> {
                if (size < 4) return true;
                if (headBytes[0] != (byte) 0xFF || headBytes[1] != (byte) 0xD8) {
                    return true;
                }
                return !isImageHeaderParsable(file);
            }
            case "png" -> {
                if (size < 8 || headBytes.length < 8) return true;
                if (headBytes[0] != (byte) 0x89 || headBytes[1] != 0x50 ||
                    headBytes[2] != 0x4E || headBytes[3] != 0x47 ||
                    headBytes[4] != 0x0D || headBytes[5] != 0x0A ||
                    headBytes[6] != 0x1A || headBytes[7] != 0x0A) {
                    return true;
                }
                return !isImageHeaderParsable(file);
            }
            case "gif" -> {
                if (size < 6 || headBytes.length < 6) return true;
                if (headBytes[0] != 'G' || headBytes[1] != 'I' || headBytes[2] != 'F' ||
                    headBytes[3] != '8' || (headBytes[4] != '7' && headBytes[4] != '9') ||
                    headBytes[5] != 'a') {
                    return true;
                }
                return !isImageHeaderParsable(file);
            }
            case "bmp" -> {
                if (size < 14 || headBytes.length < 2) return true;
                if (headBytes[0] != 'B' || headBytes[1] != 'M') {
                    return true;
                }
                return !isImageHeaderParsable(file);
            }
            case "tiff", "tif" -> {
                if (size < 8 || headBytes.length < 4) return true;
                if (!isTiffHeader(headBytes)) {
                    return true;
                }
                return !isImageHeaderParsable(file);
            }
            case "webp" -> {
                if (size < 12 || headBytes.length < 12) return true;
                if (headBytes[0] != 'R' || headBytes[1] != 'I' || headBytes[2] != 'F' || headBytes[3] != 'F' ||
                    headBytes[8] != 'W' || headBytes[9] != 'E' || headBytes[10] != 'B' || headBytes[11] != 'P') {
                    return true;
                }
                return false;
            }
            case "heic", "heif", "avif" -> {
                if (size < 16 || headBytes.length < 12) return true;
                if (headBytes[4] != 'f' || headBytes[5] != 't' || headBytes[6] != 'y' || headBytes[7] != 'p') {
                    return true;
                }
                String brand = new String(headBytes, 8, 4, StandardCharsets.ISO_8859_1).toLowerCase();
                return !HEIC_BRANDS.contains(brand);
            }
            case "raw", "dng", "cr2", "nef", "arw" -> {
                if (size < 1024 || headBytes.length < 4) return true;
                return !isTiffHeader(headBytes);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean isTiffHeader(byte[] headBytes) {
        if (headBytes.length < 4) return false;
        boolean littleEndian = headBytes[0] == 'I' && headBytes[1] == 'I' && headBytes[2] == 0x2A && headBytes[3] == 0x00;
        boolean bigEndian = headBytes[0] == 'M' && headBytes[1] == 'M' && headBytes[2] == 0x00 && headBytes[3] == 0x2A;
        return littleEndian || bigEndian;
    }

    private boolean isImageHeaderParsable(Path file) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file.toFile())) {
            if (iis == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, false);
                return reader.getWidth(0) > 0 && reader.getHeight(0) > 0;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isVideoCorrupted(Path file, String fileName, long size) throws IOException {
        String ext = getExtension(fileName);
        if (ext == null) return true;

        byte[] headBytes = readHeaderBytes(file, 32);
        if (headBytes == null || headBytes.length < 4) return true;

        return switch (ext) {
            case "mp4", "mov", "m4v", "3gp" -> isMp4Corrupted(file, headBytes, size);
            case "mkv", "webm" -> isMkvCorrupted(headBytes, size);
            case "avi" -> isAviCorrupted(headBytes, size);
            case "flv" -> isFlvCorrupted(headBytes, size);
            case "mpg", "mpeg" -> isMpegCorrupted(headBytes, size);
            case "wmv" -> isWmvCorrupted(headBytes, size);
            case "mts", "m2ts", "ts" -> isTransportStreamCorrupted(headBytes, size);
            default -> false;
        };
    }

    private boolean isMp4Corrupted(Path file, byte[] headBytes, long size) throws IOException {
        if (size < 16 || headBytes.length < 8) return true;

        String firstBoxType = new String(headBytes, 4, 4, StandardCharsets.ISO_8859_1);
        if (!MP4_FIRST_BOXES.contains(firstBoxType)) {
            return true;
        }

        // Only search moov atom when container indicates standard ftyp structure
        if ("ftyp".equals(firstBoxType)) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                ByteBuffer header = ByteBuffer.allocate(8);
                byte[] typeBytes = new byte[4];
                boolean hasMoov = false;
                long position = 0;

                while (position + 8 <= size) {
                    channel.position(position);
                    header.clear();
                    if (channel.read(header) < 8) break;
                    header.flip();

                    long boxSize = Integer.toUnsignedLong(header.getInt());
                    header.get(typeBytes);
                    String boxType = new String(typeBytes, StandardCharsets.ISO_8859_1);

                    if ("moov".equals(boxType)) {
                        hasMoov = true;
                        break;
                    }

                    if (boxSize == 1) { // 64-bit size
                        ByteBuffer ext = ByteBuffer.allocate(8);
                        if (channel.read(ext) < 8) break;
                        ext.flip();
                        boxSize = ext.getLong();
                    } else if (boxSize == 0) {
                        boxSize = size - position;
                    }

                    if (boxSize < 8) break;
                    position += boxSize;
                }
                return !hasMoov;
            } catch (Exception e) {
                log.debug("Error checking MP4 corruption for {}: {}", file, e.getMessage());
                return true;
            }
        }

        return false;
    }

    private boolean isMkvCorrupted(byte[] headBytes, long size) {
        if (size < 8 || headBytes.length < 4) return true;
        return headBytes[0] != 0x1A || headBytes[1] != 0x45 ||
               headBytes[2] != (byte) 0xDF || headBytes[3] != (byte) 0xA3;
    }

    private boolean isAviCorrupted(byte[] headBytes, long size) {
        if (size < 12 || headBytes.length < 12) return true;
        if (headBytes[0] != 'R' || headBytes[1] != 'I' || headBytes[2] != 'F' || headBytes[3] != 'F') {
            return true;
        }
        String type = new String(headBytes, 8, 4, StandardCharsets.ISO_8859_1);
        return !"AVI ".equals(type) && !"AVIX".equals(type);
    }

    private boolean isFlvCorrupted(byte[] headBytes, long size) {
        if (size < 9 || headBytes.length < 4) return true;
        return headBytes[0] != 'F' || headBytes[1] != 'L' || headBytes[2] != 'V' || headBytes[3] != 0x01;
    }

    private boolean isMpegCorrupted(byte[] headBytes, long size) {
        if (size < 8 || headBytes.length < 4) return true;
        return headBytes[0] != 0x00 || headBytes[1] != 0x00 || headBytes[2] != 0x01 ||
               (headBytes[3] != (byte) 0xBA && headBytes[3] != (byte) 0xB3);
    }

    private boolean isWmvCorrupted(byte[] headBytes, long size) {
        if (size < 16 || headBytes.length < 16) return true;
        return !Arrays.equals(Arrays.copyOf(headBytes, 16), WMV_GUID);
    }

    private boolean isTransportStreamCorrupted(byte[] headBytes, long size) {
        if (size < 188 || headBytes.length < 5) return true;
        return headBytes[0] != 0x47 && headBytes[4] != 0x47;
    }

    private boolean isArchiveCorrupted(Path file, String fileName, long size) {
        String lower = fileName.toLowerCase();
        byte[] headBytes = readHeaderBytes(file, 4);
        if (headBytes == null || headBytes.length < 2) return true;

        if (lower.endsWith(".zip")) {
            if (headBytes[0] != 'P' || headBytes[1] != 'K') return true;
        } else if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".gz")) {
            if (headBytes[0] != (byte) 0x1F || headBytes[1] != (byte) 0x8B) return true;
        } else if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".bz2")) {
            if (headBytes.length < 3 || headBytes[0] != 'B' || headBytes[1] != 'Z' || headBytes[2] != 'h') return true;
        } else if (lower.endsWith(".tar") && size < 512) {
            return true;
        }

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file));
             ArchiveInputStream<?> ais = createArchiveInputStream(file, in)) {
            return ais.getNextEntry() == null;
        } catch (Exception e) {
            return true;
        }
    }

    public ArchiveInputStream<?> createArchiveInputStream(Path file, InputStream is) throws Exception {
        String fileName = file.getFileName().toString().toLowerCase();
        InputStream buffered = is instanceof BufferedInputStream ? is : new BufferedInputStream(is, 65536);

        if (fileName.endsWith(".zip")) {
            return new ZipArchiveInputStream(buffered);
        } else if (fileName.endsWith(".tar")) {
            return new TarArchiveInputStream(buffered);
        } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            return new TarArchiveInputStream(new GzipCompressorInputStream(buffered));
        } else if (fileName.endsWith(".tar.bz2") || fileName.endsWith(".tbz2")) {
            return new TarArchiveInputStream(new BZip2CompressorInputStream(buffered));
        } else {
            throw new UnsupportedOperationException("Unsupported archive format: " + fileName);
        }
    }

    public Path quarantineFile(Path sourceFile, Path baseMemoriesDir, int year, String mediaType, String reason) {
        try {
            String folderName = mediaType.endsWith("s") ? mediaType : mediaType + "s";
            Path quarantineDir = baseMemoriesDir.resolve("quarantine")
                    .resolve(String.valueOf(year))
                    .resolve(folderName);
            Files.createDirectories(quarantineDir);

            String fileName = sourceFile.getFileName().toString();
            Path targetFile = quarantineDir.resolve(fileName);
            int counter = 1;
            while (Files.exists(targetFile)) {
                int dot = fileName.lastIndexOf('.');
                String base = dot > 0 ? fileName.substring(0, dot) : fileName;
                String ext = dot > 0 ? fileName.substring(dot) : "";
                targetFile = quarantineDir.resolve(base + "_" + counter + ext);
                counter++;
            }

            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Quarantined corrupted file: {} -> {} (reason: {})", sourceFile, targetFile, reason);
            return targetFile;
        } catch (IOException e) {
            log.error("Failed to quarantine corrupted file {}: {}", sourceFile, e.getMessage());
            return null;
        }
    }

    public Path quarantineAndRemove(Path sourceFile, Path baseMemoriesDir, int year, String mediaType, String reason) {
        Path quarantined = quarantineFile(sourceFile, baseMemoriesDir, year, mediaType, reason);
        if (quarantined != null) {
            try {
                Files.deleteIfExists(sourceFile);
            } catch (IOException e) {
                log.warn("Failed to delete source file after quarantine: {}", sourceFile, e);
            }
        }
        return quarantined;
    }

    private byte[] readHeaderBytes(Path file, int count) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(channel.size(), count));
            channel.read(buffer);
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toLowerCase();
    }
}
