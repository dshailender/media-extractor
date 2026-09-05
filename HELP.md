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
# Unified extraction + classification + move to quarantine (Default):
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar [<sourceDir>]

# Or use the convenience script:
./scripts/run_pipeline.sh /path/to/source
```

**Arguments & Shorthand Flags:**
- `<sourceDir>` (optional): Source directory containing media and archives. Defaults to `C:\Users\Shailender\projects\backup`
- `--dry-run`: Runs classifier in audit mode (inspects and logs without moving memes/greetings).
- `--no-classify`: Skips the Python classifier entirely (pure extraction only).
- `--no-quarantine`: Moves memes/greetings to `~/memories/{YYYY}/` instead of `~/memories/quarantine/{YYYY}/`.
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

### Meme & Greeting Classification (AI Sidecar)

To segregate memes and greetings from extracted personal photos into `~/memories/{YYYY}/memes/` and `~/memories/{YYYY}/greetings/`:

```bash
# 1. Setup Python virtual environment (one-time setup)
./scripts/setup_env.sh

# 2. Dry-run inspection (audits without moving files)
.venv/bin/python scripts/classify_memes.py --source-dir ~/memories/ --action dry-run

# 3. Move memes & greetings to dedicated memory folders
.venv/bin/python scripts/classify_memes.py --source-dir ~/memories/ --action move

# Optional: Enable Gemini 2.5 Flash Free-Tier Fallback for low-confidence files (< 0.65)
export GEMINI_API_KEY="your_api_key_here"
.venv/bin/python scripts/classify_memes.py --source-dir ~/memories/ --action move --rate-limit-rpm 12.0
```

**Features:**
- **Tier 1 (Instant EXIF Hardware Prior)**: Real camera photos with `Make`/`Model` tags bypass AI models at 0ms.
- **Tier 2 (EasyOCR Multilingual Text Extraction)**: Fast CPU-optimized OCR using multi-variant contrast preprocessing, extracting bounding boxes, text area ratio, and multilingual keyword hits (English, Hindi, festival wishes, quotes, meme catchphrases, document terms).
- **Tier 3 (Local CLIP Zero-Shot Prompt Ensemble)**: Fuses multi-prompt text-image similarity with balanced category aggregation, margin, and normalized entropy on CPU.
- **Tier 4 (Hierarchical Decision Engine & Uncertainty Scoring)**: Fuses EXIF, OCR text, aspect ratios, caption layouts, and CLIP scores with calibrated uncertainty $U \in [0, 1]$.
- **Tier 5 (Structured Multimodal Gemini Fallback)**: Rate-limited free-tier fallback (12 RPM) for ambiguous images, passing OCR text and signals for strict JSON classification.
- **Review Mode**: Flags uncertain files ($U \ge 0.40$) to `review_queue.csv` and suppresses file movements to guarantee zero data loss.
- **Ground-Truth Evaluator**: Built-in CLI evaluation (`--evaluate <labeled.csv>`) computing full confusion matrices, per-class Precision/Recall/F1, and overall accuracy.
- **Deduplication & Resume**: Writes real-time audit records to 17-column `classification_results.csv` and skips already processed files on restart.

### Advanced CLI Options:

```bash
# Evaluate classifier against ground-truth labeled dataset
.venv/bin/python scripts/classify_memes.py --evaluate test_benchmark_2017.csv --no-gemini

# Run with custom OCR languages (e.g. English + Hindi)
.venv/bin/python scripts/classify_memes.py --source-dir ~/memories/ --ocr-languages en hi

# Review mode with custom uncertainty threshold
.venv/bin/python scripts/classify_memes.py --source-dir ~/memories/ --action move --review-threshold 0.35 --review-csv review_queue.csv

# Rescue valid personal photos mistakenly quarantined in previous runs
.venv/bin/python scripts/classify_memes.py --source-dir ~/memories/quarantine/2017 --action move --rescue
```

The Python classifier is enabled by default to run automatically after extraction.
Its behavior is configured in `src/main/resources/application.properties` or overridden via CLI flags:

```properties
media-extractor.classifier-enabled=true
media-extractor.classifier-action=move
media-extractor.classifier-quarantine=true
media-extractor.classifier-python=.venv/bin/python
media-extractor.classifier-script=scripts/classify_memes.py
media-extractor.classifier-working-directory=.
```

- By default, action is `move` and quarantine is `true`, routing memes and greetings directly into `~/memories/quarantine/{YYYY}/`.
- Use `--dry-run` to inspect without moving, or `--no-classify` to disable the classifier.
- Java writes a temporary manifest containing only photo files successfully extracted by the current run, including photos from nested archives, and passes it via `--input-manifest`. Existing files elsewhere under `~/memories/` are not touched. The Python script can still be run standalone directly with `--source-dir` for a full directory scan.

Automatic classification is skipped for `--sanitize`/`--clean`, the `test` profile, and runs that
extract no photos. Python startup failures, nonzero exit codes, and timeouts are logged while the
completed Java extraction remains successful.

