# Read Me First
The following was discovered as part of building this project:

* The original package name 'com.example.media-extractor' is invalid and this project uses 'com.example.mediaextractor' instead.

# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/3.5.3/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/3.5.3/maven-plugin/build-image.html)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.

### Use below command to run the application

```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar [<sourceDir>]
```

**Arguments:**
- `<sourceDir>` (optional): Source directory containing media and archives. Defaults to `C:\Users\Shailender\projects\backup`
- `--sanitize` or `--clean`: Runs in sanitization mode to audit existing files in `~/memories/`, quarantines any corrupted files, removes duplicates, and prunes empty directories without performing a new source extraction.

**Output Directory Structure:**

Media files are extracted and organized by year in the user's home directory:
```
~/memories/
  ├── 2023/
  │   ├── photos/
  │   │   ├── photo1.jpg
  │   │   ├── photo2.png
  │   │   └── ...
  │   └── videos/
  │       ├── video1.mp4
  │       └── ...
  ├── 2024/
  │   ├── photos/
  │   │   └── ...
  │   └── videos/
  │       └── ...
  └── 2025/
      ├── photos/
      └── videos/
```

**Key Features:**
- Photos extracted to: `~/memories/{YYYY}/photos/`
- Videos extracted to: `~/memories/{YYYY}/videos/`
- Corrupted media quarantined to: `~/memories/quarantine/{YYYY}/`
- Year ({YYYY}) is determined by intelligent multi-source hierarchy:
  1. Photo EXIF metadata (`DateTimeOriginal` from header buffer)
  2. Video container metadata (MP4/QuickTime `mvhd` creation timestamp)
  3. Google Photos sidecar `.json` metadata (`photoTakenTime.timestamp`)
  4. Filename capture date patterns (e.g. `IMG_20210815_142301.jpg`)
  5. Filesystem last modified time (fallback)
- Extended format support:
  - **Photos**: jpg, jpeg, png, gif, bmp, tiff, tif, webp, raw, heic, heif, avif, dng, cr2, nef, arw
  - **Videos**: mp4, mov, avi, mkv, flv, wmv, m4v, mpg, mpeg, 3gp, webm, mts, m2ts, ts
  - **Archives**: zip, tar, gz, tgz, bz2, tbz2, tar.gz, tar.bz2
- All files stored in a flat structure (no subdirectories created for source folder hierarchy)
- Original capture timestamps preserved on destination files (`lastModifiedTime`)
- Fast structural corruption detection without heavy pixel decoding; corrupted files are safely moved to quarantine
- Multi-tier deduplication (File size $\rightarrow$ Sparse head/tail hash $\rightarrow$ Full SHA-256 digest)
- Cross-run idempotent deduplication: pre-indexes existing destination files so re-running produces zero duplicate files
- Filename collisions handled with atomic kernel reservation (`O_CREAT | O_EXCL`) and numeric suffixes (e.g., `photo_1.jpg`)
- Single-pass streaming I/O with on-the-fly digest calculation for both direct copies and archive extractions
- Each run writes JSON and HTML extraction reports to the output directory by default
- Progress, extraction rate, duplicate counts, corruption counts, quarantine counts, and failure reasons are logged

### Performance and reports

The following Spring properties tune large-backup processing:

```properties
media-extractor.threads=0
media-extractor.max-in-flight=256
media-extractor.progress-interval-ms=5000
media-extractor.report-directory=
media-extractor.quarantine-enabled=true
media-extractor.preserve-timestamps=true
```

`media-extractor.threads=0` uses virtual threads. Set it to a positive value to use a fixed-size pool. `media-extractor.max-in-flight` bounds pending and active extraction work so scanning large backups does not create an unbounded task queue. `media-extractor.quarantine-enabled` controls whether corrupted files are safely isolated in `~/memories/quarantine/` (default `true`). `media-extractor.preserve-timestamps` sets the destination file's filesystem modified time to the extracted capture date (default `true`). Progress is logged periodically and includes scanned, completed, in-flight, extracted, duplicate, corrupt, quarantined, failed, and rate metrics.

Reports are named `media-extraction-report-<timestamp>.json` and `.html`. They include source/output paths, timing, throughput, bytes written, peak in-flight work, per-type/year extraction counts, and item-level duplicate/corruption/failure details. Set `media-extractor.report-directory` to place them elsewhere.

### Examples

Extract media with default source directory:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar
```

Extract from custom source directory:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar /home/user/MyPhotos
```

Audit and sanitize existing memories (quarantine corrupt files, prune empty folders):
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar --sanitize
```

On Windows:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar C:\Users\YourName\Pictures
```
