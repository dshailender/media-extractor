# AI Coding Agent Guide for Media Extractor

## Project Overview

**Media Extractor** is a Spring Boot CLI application that extracts photos and videos from directories and nested archives, organizing them by capture year in a flat directory structure.

**Core workflow:**
1. **Extraction phase**: Recursively scan source directory, extract media from archives, copy photos/videos to `~/archive/memories/{YYYY}/photos` and `~/archive/memories/{YYYY}/videos` (where {YYYY} is the year extracted from file modification time)
2. **Flat structure**: No subdirectories preserved from source - all files organized only by year and type

## Essential Build & Test Commands

```bash
# Build the project
./mvnw clean package

# Run extraction (default source: C:\Users\Shailender\projects\backup)
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar

# Extract from custom source directory
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar /path/to/source

# Run tests
./mvnw test
```

See [HELP.md](HELP.md) for detailed usage examples and output directory structure.

## Project Structure

```
src/main/java/com/example/mediaextractor/
  ├── MediaExtractorApplication.java      # CLI entry point, orchestrates workflow
  ├── MediaExtractorService.java          # Core extraction logic (archives, file detection)
  ├── IncrementalBackupService.java       # Multi-part 7-Zip incremental backup & integrity testing
  └── MediaConsolidationService.java      # (Deprecated) Consolidation & collision handling - no longer used

src/test/java/com/example/mediaextractor/
  ├── MediaExtractorServiceTest.java              # Comprehensive tests for year-based flat extraction
  ├── IncrementalBackupServiceTest.java           # Tests for incremental packaging & 7z volume splitting
  ├── MediaConsolidationServiceConcurrencyTest.java  # Legacy collision detection verification
  └── MediaExtractorApplicationTests.java            # Context load test
```

## Architecture & Key Patterns

### 1. **Year-Based Organization**
- Files automatically organized by capture year: `~/memories/{YYYY}/photos` and `~/memories/{YYYY}/videos`
- Year extracted from file's last modified time (fallback to current year if unavailable)
- **Key place**: `MediaExtractorService.getYearFromFile()` and `extractYearFromArchiveEntry()`

### 2. **Flat Directory Structure**
- All files stored directly in year-based directories with no subdirectories
- Source directory hierarchy completely ignored during extraction
- Example: `~/memories/2024/photos/photo1.jpg`, `~/memories/2024/photos/photo2.jpg` (not organized by source folders)
- **Key method**: `MediaExtractorService.copyMediaFileFlattened()`

### 3. **Concurrent Processing with Virtual Threads**
- Uses Spring Boot 4.1.1 with Java 25 virtual threads for high-concurrency I/O
- `ExecutorService` with `newVirtualThreadPerTaskExecutor()` for extraction phase
- **Key places**: `MediaExtractorApplication.run()`, `MediaExtractorService.processArchive()`

### 4. **Archive Handling**
- Supports: ZIP, TAR, TAR.GZ, TAR.BZ2 (single and nested archives)
- Uses Apache Commons Compress for decompression
- Nested archives are extracted to temp files and recursively processed via thread executor
- Archive entry filenames are flattened (directory structure within archives ignored)
- **Key file extension sets**: `PHOTO_EXTENSIONS`, `VIDEO_EXTENSIONS`, `ARCHIVE_EXTENSIONS` in `MediaExtractorService`

### 6. **File Type Detection & Collision Handling**
- Case-insensitive extension matching (lowercase conversion)
- Three categories: photos, videos, archives
- Photo formats: jpg, jpeg, png, gif, bmp, tiff, tif, webp, raw
- Video formats: mp4, mov, avi, mkv, flv, wmv, m4v, mpg, mpeg, 3gp
- Archive formats: zip, tar, gz, tgz, bz2, tbz2, tar.gz, tar.bz2
- Filename collisions handled by appending `_1`, `_2`, etc. suffix
- **Key method**: `MediaExtractorService.getUniqueFileName()`

### 7. **Incremental Backup & Multi-Part 7-Zip Packaging**
- `--incremental` flag runs incremental packaging of newly added media
- Only newly written files in the current run (`MediaExtractorService.newlyExtractedFiles`) are packaged
- Resolves post-classification relocated files (e.g. memes/greetings moved to `quarantine/{YYYY}/memes`) to preserve target filesystem structure
- Includes current run's extraction reports (`media-extraction-report-*.json/html`) and CSV logs (`classification_results.csv`, `review_queue.csv`)
- Invokes native `/usr/bin/7z` with `-v4g`, `-mx=1` (fast compression), and `-mmt=on`
- Tests archive integrity via `7z t` and emits a detailed JSON receipt in `<output>/backups/`
- Local files are ready for manual user transfer to external drive

## Important Implementation Details

### Path Handling
- Output base directory: `~/archive/memories/` (user home directory)
- Year-based subdirectories created on-demand: `~/archive/memories/{YYYY}/photos` and `~/archive/memories/{YYYY}/videos`
- All paths normalized and made absolute: `.toAbsolutePath().normalize()`
- Source directory path ignored - only year and media type determine output location

### Thread Safety
- `AtomicInteger queuedItems` tracks extraction queue size
- Virtual threads used for I/O-bound work, not compute-bound
- No file-level locking needed (single file per task in flat structure)

### Test Profile
- Tests run with `@ActiveProfiles("test")` to skip extraction in `MediaExtractorApplication.run()`
- Concurrency test creates 100 files with same name in different subdirectories to verify collision handling

## Common Patterns for Modifications

### Adding a New File Type
1. Add extension to appropriate set (`PHOTO_EXTENSIONS`, `VIDEO_EXTENSIONS`, or `ARCHIVE_EXTENSIONS`)
2. Update detection methods: `isPhoto()`, `isVideo()`, or `isArchiveFile()`
3. For extraction: files are already routed correctly based on type
4. For archives: update `createArchiveInputStream()` with new decompression logic if needed

### Modifying Year Extraction Logic
- Update `getYearFromFile()` method to extract year from different source (e.g., EXIF data for photos)
- Update `extractYearFromArchiveEntry()` for archive entry metadata
- Modify fallback logic in `LocalDateTime.now().getYear()` if needed

### Handling New Archive Types
1. Add extension to `ARCHIVE_EXTENSIONS`
2. Create new `ArchiveInputStream` case in `createArchiveInputStream()`
3. Return appropriate decompression wrapper (e.g., `new TarArchiveInputStream(new MyCompressor(is))`)

## Testing Conventions

- JUnit 5 with Spring Boot Test framework
- Concurrency tests verify no data loss or missed files under concurrent load
- Use `Files.walk()` and temp directories for file operations
- Always clean up resources in `@AfterEach` methods

## Known Constraints & Pitfalls

1. **Hardcoded Default Source**: Default source path is Windows-specific (`C:\Users\Shailender\projects\backup`). Linux/Mac users must provide custom path.
2. **Package Name**: Original invalid package name `com.example.media-extractor` changed to `com.example.mediaextractor` (hyphens removed).
3. **Long-Running Operations**: No timeout enforcement; max 1 day timeout for executor shutdown.
4. **Temp Directory Cleanup**: Relies on `cleanupTempDir()` in finally block; manual intervention needed if process crashes.
5. **Memory with Large Archives**: Nested archive extraction uses temporary files but still requires disk space for decompression.
6. **Flat Structure Collisions**: More filename collisions likely with flat structure; collision handling appends numeric suffixes.
7. **Year Extraction**: Based on file's last modified time; doesn't read EXIF data. For photos, consider extending `getYearFromFile()` to read EXIF metadata for more accurate capture year.

## Performance Considerations

- Virtual threads excel at I/O-bound work (file I/O, archive reading)
- Flat structure reduces directory tree depth, improving filesystem performance
- Year-based organization eliminates need for post-processing consolidation
- Collision handling is fast (single iteration check until unique name found)
- No separate consolidation phase needed

## File Linkages
- See [HELP.md](HELP.md) for complete command-line examples and output directory structure
- Check pom.xml for dependency versions and build configuration
