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
- Year ({YYYY}) is determined by the file's last modified time
- All files stored in a flat structure (no subdirectories created for source folder hierarchy)
- Filename collisions handled by appending numeric suffix (e.g., `photo_1.jpg`, `photo_2.jpg`)
- Supports nested archives: ZIP, TAR, TAR.GZ, TAR.BZ2
- Single-phase extraction (no separate consolidation step needed)
- EXIF DateTimeOriginal metadata is preferred over the file timestamp when available
- Identical file content is extracted once and subsequent duplicates are skipped
- Empty or recognizable corrupted media is skipped with a warning
- Each run writes JSON and HTML extraction reports to the output directory by default
- Progress, extraction rate, duplicate counts, corruption counts, and failure reasons are logged

### Performance and reports

The following Spring properties tune large-backup processing:

```properties
media-extractor.threads=0
media-extractor.max-in-flight=256
media-extractor.progress-interval-ms=5000
media-extractor.report-directory=
```

`media-extractor.threads=0` uses virtual threads. Set it to a positive value to use a fixed-size pool. `media-extractor.max-in-flight` bounds pending and active extraction work so scanning large backups does not create an unbounded task queue. Progress is logged periodically and includes scanned, completed, in-flight, extracted, duplicate, corrupt, failed, and rate metrics.

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

On Windows:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar C:\Users\YourName\Pictures
```
