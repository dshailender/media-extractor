package com.example.mediaextractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MediaMetadataService {

    private static final Logger log = LoggerFactory.getLogger(MediaMetadataService.class);

    public static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp", "raw",
            "heic", "heif", "avif", "dng", "cr2", "nef", "arw"
    );

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mov", "avi", "mkv", "flv", "wmv", "m4v", "mpg", "mpeg", "3gp",
            "webm", "mts", "m2ts", "ts"
    );

    public static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "tar", "gz", "tgz", "bz2", "tbz2"
    );

    private static final long MP4_EPOCH_OFFSET_SECONDS = 2082844800L; // Seconds from 1904-01-01 to 1970-01-01

    private static final Pattern FILENAME_DATE_PATTERN = Pattern.compile(
            "(?:^|[^0-9])((?:19[7-9]\\d|20[0-4]\\d)[-_]?(0[1-9]|1[0-2])[-_]?(0[1-9]|[12]\\d|3[01]))" +
            "(?:[-_T ]?([01]\\d|2[0-3])([0-5]\\d)([0-5]\\d))?"
    );

    private static final Pattern SIDECAR_TIMESTAMP_PATTERN = Pattern.compile(
            "\"timestamp\"\\s*:\\s*\"?([0-9]{10,13})\"?"
    );

    private String dateFormatPattern = "yyyyMMdd_HHmmss";
    private String photoPrefix = "IMG_";
    private String videoPrefix = "MOV_";
    private DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public MediaMetadataService() {
    }

    public MediaMetadataService(String dateFormatPattern, String photoPrefix, String videoPrefix) {
        if (dateFormatPattern != null && !dateFormatPattern.isBlank()) {
            this.dateFormatPattern = dateFormatPattern;
            this.dateFormatter = DateTimeFormatter.ofPattern(dateFormatPattern);
        }
        if (photoPrefix != null && !photoPrefix.isBlank()) {
            this.photoPrefix = photoPrefix;
        }
        if (videoPrefix != null && !videoPrefix.isBlank()) {
            this.videoPrefix = videoPrefix;
        }
    }

    public void setDateFormatPattern(String dateFormatPattern) {
        if (dateFormatPattern != null && !dateFormatPattern.isBlank()) {
            this.dateFormatPattern = dateFormatPattern;
            this.dateFormatter = DateTimeFormatter.ofPattern(dateFormatPattern);
        }
    }

    public void setPhotoPrefix(String photoPrefix) {
        if (photoPrefix != null && !photoPrefix.isBlank()) {
            this.photoPrefix = photoPrefix;
        }
    }

    public void setVideoPrefix(String videoPrefix) {
        if (videoPrefix != null && !videoPrefix.isBlank()) {
            this.videoPrefix = videoPrefix;
        }
    }

    public String getDateFormatPattern() {
        return dateFormatPattern;
    }

    public String getPhotoPrefix() {
        return photoPrefix;
    }

    public String getVideoPrefix() {
        return videoPrefix;
    }

    public String generateTargetFileName(String mediaType, Instant captureInstant, String originalFileName) {
        String prefix = "video".equalsIgnoreCase(mediaType) ? videoPrefix : photoPrefix;
        Instant instant = captureInstant != null ? captureInstant : Instant.now();
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        String formatted = ldt.format(dateFormatter);
        String ext = getExtension(originalFileName);
        String extensionSuffix = (ext != null && !ext.isEmpty()) ? "." + ext.toLowerCase() : "";
        return prefix + formatted + extensionSuffix;
    }

    public record Metadata(int year, Instant captureInstant, String source) { }

    public boolean isPhoto(String fileName) {
        String ext = getExtension(fileName);
        return ext != null && PHOTO_EXTENSIONS.contains(ext);
    }

    public boolean isVideo(String fileName) {
        String ext = getExtension(fileName);
        return ext != null && VIDEO_EXTENSIONS.contains(ext);
    }

    public boolean isArchiveFile(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.bz2")) return true;
        String ext = getExtension(fileName);
        return ext != null && ARCHIVE_EXTENSIONS.contains(ext);
    }

    public boolean isSupportedMedia(String fileName) {
        return isPhoto(fileName) || isVideo(fileName) || isArchiveFile(fileName);
    }

    public Metadata extractMetadata(Path file) {
        String fileName = file.getFileName().toString();

        // 1. Photo EXIF metadata
        if (isPhoto(fileName)) {
            Metadata exifMeta = extractExifMetadata(file);
            if (exifMeta != null) {
                return exifMeta;
            }
        }

        // 2. Video container metadata (MP4 / MOV creation time)
        if (isVideo(fileName)) {
            Metadata videoMeta = extractVideoMetadata(file);
            if (videoMeta != null) {
                return videoMeta;
            }
        }

        // 3. Sidecar JSON metadata (Google Photos Takeout)
        Metadata sidecarMeta = extractSidecarMetadata(file);
        if (sidecarMeta != null) {
            return sidecarMeta;
        }

        // 4. Filename date pattern parsing
        Metadata filenameMeta = extractFilenameDateMetadata(fileName);
        if (filenameMeta != null) {
            return filenameMeta;
        }

        // 5. Fallback: Filesystem last modified time
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            Instant instant = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis());
            int year = LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).getYear();
            return new Metadata(year, instant, "filesystem");
        } catch (IOException e) {
            log.debug("Failed to read file attributes for {}: {}. Using current year", file, e.getMessage());
            Instant now = Instant.now();
            return new Metadata(LocalDateTime.ofInstant(now, ZoneId.systemDefault()).getYear(), now, "fallback");
        }
    }

    public int extractYear(Path file) {
        return extractMetadata(file).year();
    }

    /**
     * Reads up to 128KB from the beginning of the photo file to extract EXIF DateTimeOriginal.
     * This avoids reading multi-megabyte files into heap.
     */
    public Metadata extractExifMetadata(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            int bufferSize = (int) Math.min(channel.size(), 131072); // 128 KB
            if (bufferSize < 10) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            channel.read(buffer);
            byte[] bytes = buffer.array();

            // Check JPEG magic
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
                return parseJpegExif(bytes, bufferSize);
            }
            // Check TIFF header directly (e.g. RAW / DNG / TIFF)
            if ((bytes[0] == 0x49 && bytes[1] == 0x49) || (bytes[0] == 0x4D && bytes[1] == 0x4D)) {
                return parseTiffExif(bytes, 0, bufferSize);
            }
        } catch (IOException e) {
            log.debug("Unable to read EXIF for {}: {}", file, e.getMessage());
        }
        return null;
    }

    private Metadata parseJpegExif(byte[] imageBytes, int length) {
        int offset = 2;
        while (offset + 4 < length) {
            if (imageBytes[offset] != (byte) 0xFF) {
                break;
            }

            int marker = imageBytes[offset + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) { // SOS or EOI
                break;
            }

            int segmentLength = ((imageBytes[offset + 2] & 0xFF) << 8) | (imageBytes[offset + 3] & 0xFF);
            int payloadStart = offset + 4;
            int payloadEnd = Math.min(length, payloadStart + segmentLength - 2);

            // APP1 marker with Exif header
            if (marker == 0xE1 && payloadStart + 6 <= payloadEnd) {
                if (imageBytes[payloadStart] == 0x45 && imageBytes[payloadStart + 1] == 0x78 &&
                    imageBytes[payloadStart + 2] == 0x69 && imageBytes[payloadStart + 3] == 0x66 &&
                    imageBytes[payloadStart + 4] == 0x00 && imageBytes[payloadStart + 5] == 0x00) {

                    return parseTiffExif(imageBytes, payloadStart + 6, payloadEnd);
                }
            }

            offset = payloadStart + segmentLength - 2;
        }
        return null;
    }

    private Metadata parseTiffExif(byte[] bytes, int tiffStart, int length) {
        if (tiffStart + 8 > length) {
            return null;
        }

        boolean littleEndian = bytes[tiffStart] == 0x49 && bytes[tiffStart + 1] == 0x49;
        boolean bigEndian = bytes[tiffStart] == 0x4D && bytes[tiffStart + 1] == 0x4D;
        if (!littleEndian && !bigEndian) {
            return null;
        }

        int magic = readUnsignedShort(bytes, tiffStart + 2, littleEndian);
        if (magic != 42) {
            return null;
        }

        int ifd0Offset = readUnsignedInt(bytes, tiffStart + 4, littleEndian);
        return extractDateFromIfd(bytes, tiffStart + ifd0Offset, littleEndian, tiffStart, length);
    }

    private Metadata extractDateFromIfd(byte[] bytes, int ifdOffset, boolean littleEndian, int tiffStart, int length) {
        if (ifdOffset < 0 || ifdOffset + 2 > length) {
            return null;
        }

        int entryCount = readUnsignedShort(bytes, ifdOffset, littleEndian);
        int cursor = ifdOffset + 2;

        for (int i = 0; i < entryCount; i++) {
            if (cursor + 12 > length) {
                return null;
            }

            int tag = readUnsignedShort(bytes, cursor, littleEndian);
            int type = readUnsignedShort(bytes, cursor + 2, littleEndian);
            int count = readUnsignedInt(bytes, cursor + 4, littleEndian);
            int valueOrOffset = readUnsignedInt(bytes, cursor + 8, littleEndian);

            // SubIFD pointer (Exif IFD)
            if (tag == 0x8769 && valueOrOffset != 0) {
                Metadata subMeta = extractDateFromIfd(bytes, tiffStart + valueOrOffset, littleEndian, tiffStart, length);
                if (subMeta != null) {
                    return subMeta;
                }
            }

            // DateTimeOriginal (0x9003), DateTimeDigitized (0x9004), DateTime (0x0132)
            if ((tag == 0x9003 || tag == 0x9004 || tag == 0x0132) && type == 2 && valueOrOffset != 0 && count > 4) {
                String dateStr = readAscii(bytes, tiffStart + valueOrOffset, count, length);
                if (dateStr != null && dateStr.length() >= 10) {
                    Metadata meta = parseExifDateString(dateStr);
                    if (meta != null) {
                        return meta;
                    }
                }
            }

            cursor += 12;
        }

        return null;
    }

    private Metadata parseExifDateString(String dateStr) {
        try {
            // Standard EXIF format: "YYYY:MM:DD HH:MM:SS"
            String cleaned = dateStr.trim();
            if (cleaned.length() >= 19 && cleaned.charAt(4) == ':' && cleaned.charAt(7) == ':') {
                int year = Integer.parseInt(cleaned.substring(0, 4));
                int month = Integer.parseInt(cleaned.substring(5, 7));
                int day = Integer.parseInt(cleaned.substring(8, 10));
                int hour = Integer.parseInt(cleaned.substring(11, 13));
                int min = Integer.parseInt(cleaned.substring(14, 16));
                int sec = Integer.parseInt(cleaned.substring(17, 19));

                if (year >= 1970 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, min, sec);
                    Instant instant = ldt.atZone(ZoneId.systemDefault()).toInstant();
                    return new Metadata(year, instant, "exif");
                }
            } else if (cleaned.length() >= 4) {
                int year = Integer.parseInt(cleaned.substring(0, 4));
                if (year >= 1970 && year <= 2100) {
                    Instant instant = LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                    return new Metadata(year, instant, "exif-year");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Parses QuickTime/MP4 container boxes to locate the 'mvhd' atom and extract creation time.
     */
    public Metadata extractVideoMetadata(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(8);
            long fileLength = channel.size();
            long position = 0;

            while (position + 8 <= fileLength) {
                channel.position(position);
                header.clear();
                if (channel.read(header) < 8) break;
                header.flip();

                long boxSize = Integer.toUnsignedLong(header.getInt());
                byte[] typeBytes = new byte[4];
                header.get(typeBytes);
                String boxType = new String(typeBytes, StandardCharsets.ISO_8859_1);

                long payloadOffset = 8;
                if (boxSize == 1) { // 64-bit size
                    ByteBuffer extHeader = ByteBuffer.allocate(8);
                    if (channel.read(extHeader) < 8) break;
                    extHeader.flip();
                    boxSize = extHeader.getLong();
                    payloadOffset = 16;
                } else if (boxSize == 0) {
                    boxSize = fileLength - position;
                }

                if (boxSize < payloadOffset) break;

                if ("moov".equals(boxType)) {
                    // Search inside moov for mvhd
                    Metadata meta = scanMoovForMvhd(channel, position + payloadOffset, position + boxSize);
                    if (meta != null) {
                        return meta;
                    }
                }

                position += boxSize;
            }
        } catch (Exception e) {
            log.debug("Unable to read video metadata from {}: {}", file, e.getMessage());
        }
        return null;
    }

    private Metadata scanMoovForMvhd(FileChannel channel, long moovStart, long moovEnd) throws IOException {
        long current = moovStart;
        ByteBuffer buffer = ByteBuffer.allocate(8);

        while (current + 8 <= moovEnd) {
            channel.position(current);
            buffer.clear();
            if (channel.read(buffer) < 8) break;
            buffer.flip();

            long boxSize = Integer.toUnsignedLong(buffer.getInt());
            byte[] typeBytes = new byte[4];
            buffer.get(typeBytes);
            String boxType = new String(typeBytes, StandardCharsets.ISO_8859_1);

            long payloadOffset = 8;
            if (boxSize == 1) {
                ByteBuffer ext = ByteBuffer.allocate(8);
                if (channel.read(ext) < 8) break;
                ext.flip();
                boxSize = ext.getLong();
                payloadOffset = 16;
            } else if (boxSize == 0) {
                boxSize = moovEnd - current;
            }

            if ("mvhd".equals(boxType) && boxSize >= payloadOffset + 12) {
                ByteBuffer mvhdPayload = ByteBuffer.allocate(24);
                channel.position(current + payloadOffset);
                channel.read(mvhdPayload);
                mvhdPayload.flip();

                int version = mvhdPayload.get() & 0xFF;
                mvhdPayload.get(); mvhdPayload.get(); mvhdPayload.get(); // 3 flags bytes

                long creationSeconds;
                if (version == 1 && mvhdPayload.remaining() >= 8) {
                    creationSeconds = mvhdPayload.getLong();
                } else if (version == 0 && mvhdPayload.remaining() >= 4) {
                    creationSeconds = Integer.toUnsignedLong(mvhdPayload.getInt());
                } else {
                    break;
                }

                long unixSeconds = creationSeconds - MP4_EPOCH_OFFSET_SECONDS;
                if (unixSeconds > 0) {
                    Instant instant = Instant.ofEpochSecond(unixSeconds);
                    int year = LocalDateTime.ofInstant(instant, ZoneOffset.UTC).getYear();
                    if (year >= 1970 && year <= 2100) {
                        return new Metadata(year, instant, "video-mvhd");
                    }
                }
            }

            current += boxSize;
        }

        return null;
    }

    /**
     * Checks for a Google Photos sidecar JSON file (e.g., photo.jpg.json or photo.json).
     */
    public Metadata extractSidecarMetadata(Path file) {
        Path parent = file.getParent();
        if (parent == null) return null;

        String filename = file.getFileName().toString();
        Path sidecar1 = parent.resolve(filename + ".json");
        Path sidecarCandidate = Files.exists(sidecar1) ? sidecar1 : null;

        if (sidecarCandidate == null) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0) {
                Path sidecar2 = parent.resolve(filename.substring(0, dot) + ".json");
                if (Files.exists(sidecar2)) {
                    sidecarCandidate = sidecar2;
                }
            }
        }

        if (sidecarCandidate != null && Files.isRegularFile(sidecarCandidate)) {
            try {
                String content = Files.readString(sidecarCandidate, StandardCharsets.UTF_8);
                Matcher matcher = SIDECAR_TIMESTAMP_PATTERN.matcher(content);
                if (matcher.find()) {
                    long rawTimestamp = Long.parseLong(matcher.group(1));
                    long epochSeconds = rawTimestamp > 9999999999L ? rawTimestamp / 1000 : rawTimestamp;
                    Instant instant = Instant.ofEpochSecond(epochSeconds);
                    int year = LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).getYear();
                    if (year >= 1970 && year <= 2100) {
                        return new Metadata(year, instant, "sidecar-json");
                    }
                }
            } catch (Exception e) {
                log.debug("Failed parsing sidecar JSON {}: {}", sidecarCandidate, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Extracts date from standard filename patterns like IMG_20210815_142301.jpg
     */
    public Metadata extractFilenameDateMetadata(String fileName) {
        Matcher matcher = FILENAME_DATE_PATTERN.matcher(fileName);
        if (matcher.find()) {
            try {
                String datePart = matcher.group(1).replace("-", "").replace("_", "");
                int year = Integer.parseInt(datePart.substring(0, 4));
                int month = Integer.parseInt(datePart.substring(4, 6));
                int day = Integer.parseInt(datePart.substring(6, 8));

                int hour = 12, min = 0, sec = 0;
                if (matcher.group(4) != null && matcher.group(5) != null && matcher.group(6) != null) {
                    hour = Integer.parseInt(matcher.group(4));
                    min = Integer.parseInt(matcher.group(5));
                    sec = Integer.parseInt(matcher.group(6));
                }

                if (year >= 1970 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, min, sec);
                    Instant instant = ldt.atZone(ZoneId.systemDefault()).toInstant();
                    return new Metadata(year, instant, "filename-regex");
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toLowerCase();
    }

    private String readAscii(byte[] data, int offset, int count, int length) {
        if (offset < 0 || offset + count > length) return null;
        int end = 0;
        while (end < count && offset + end < length && data[offset + end] != 0) {
            end++;
        }
        return new String(data, offset, end, StandardCharsets.US_ASCII);
    }

    private int readUnsignedShort(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset + 2 > data.length) return 0;
        if (littleEndian) {
            return ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
        }
        return (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private int readUnsignedInt(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset + 4 > data.length) return 0;
        if (littleEndian) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        }
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}

