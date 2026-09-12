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

### Running the Application

The recommended way to run Media Extractor is using the unified pipeline runner script:

```bash
# Recommended: Unified extraction, Java triage, and AI classification runner
./scripts/run_pipeline.sh <source_directory> [output_directory] [options]
```

The `run_pipeline.sh` script automatically:
1. **Verifies the Python environment**: Creates `.venv` and installs multi-modal dependencies via `./scripts/setup_env.sh` if not already present.
2. **Builds the application JAR**: Automatically packages `target/media-extractor-0.0.1-SNAPSHOT.jar` via `./mvnw package -DskipTests` if needed.
3. **Executes the full pipeline**: Extracts media into `{output_directory}/{YYYY}/` (default: `~/archive/memories/{YYYY}/`), performs instant Java camera triage, and runs multi-modal classification to quarantine memes and greetings.

Alternatively, you can run the pre-built JAR directly:
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar <source_directory> [output_directory] [options]
```

**Command-Line Flags & Options:**
- `<source_directory>`: Source directory containing media files, folders, or nested archives.
- `[output_directory]` / `-o <dir>` / `--output=<dir>` / `--output-dir=<dir>`: Output base directory (default: `~/archive/memories`). Can be supplied as a flag or as an optional second positional argument. Supports tilde expansion (`~/...` to user home).
- `--mode=<mode>`: Classifier execution and triage mode:
  - `java-triage-python` (*default*): Uses Java virtual threads for sub-millisecond EXIF/header inspection to certify obvious camera photos into `~/archive/memories/{YYYY}/photos/` (bypassing Python), while safely staging candidate images for deep Python CLIP/OCR/Gemini classification.
  - `python`: Bypasses Java triage and evaluates all extracted images directly in Python.
  - `java-only`: Runs only fast Java-level camera triage without launching the Python process.
  - `disabled`: Skips classification entirely (equivalent to `--no-classify`).
- `--dry-run`: Runs classifier in audit mode without moving or altering any files (logs decisions to CSV and console).
- `--move` (*default*): Moves classified memes and greetings into target directories.
- `--copy`: Copies memes and greetings to target directories instead of moving them.
- `--quarantine` (*default*): Routes memes and greetings into `~/archive/memories/quarantine/{YYYY}/`.
- `--classify` (or `--classify-only`): Runs in classify-only mode to evaluate and organize extracted media already in the output directory (`~/archive/memories/{YYYY}/photos/` by default or specified via `--output` / positional directory) without performing a new source extraction.
- `--resume`: Resumes classification on existing memories directory (equivalent to `--classify`).
- `--no-classify`: Skips the classification stage entirely (performs pure media extraction and deduplication).
- `--no-resume`: Disables progress-state resume and forces re-evaluation of all candidate images.
- `--state-db=<path>`: Specifies custom path for the SQLite progress-state database (default: `~/archive/memories/.classifier-state/classification.sqlite3`).
- `--incremental`: Runs incremental extraction and packages newly added files from this run into split 7-Zip backup archives (`.7z.001`, `.7z.002`, etc.) in `<output>/backups`.
- `--backup-dir=<dir>` / `-b <dir>`: Sets destination directory for 7-Zip backup parts (default: `<output>/backups`).
- `--backup-part-size=<size>`: Sets volume split size, e.g. `4g`, `2g`, `1000m` (default: `4g`).
- `--backup-compression=<level>`: Sets 7-Zip compression level: `0` (store), `1` (fast, default), `5` (normal), `9` (ultra).
- `--backup-7z-binary=<path>`: Custom path to 7-Zip executable (default: `/usr/bin/7z`).
- `--sanitize` or `--clean`: Runs in sanitization mode to audit existing files in `~/archive/memories/`, quarantines corrupted files, removes duplicates, and prunes empty directories without performing a new source extraction.

**Output Directory Structure:**

Media files are extracted and organized by year in the user's home directory:
```
~/archive/memories/
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
- Photos extracted to: `~/archive/memories/{YYYY}/photos/`
- Videos extracted to: `~/archive/memories/{YYYY}/videos/`
- Corrupted media quarantined to: `~/archive/memories/quarantine/{YYYY}/`
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
media-extractor.output-directory=
media-extractor.quarantine-enabled=true
media-extractor.preserve-timestamps=true
```

`media-extractor.threads=0` uses virtual threads. Set it to a positive value to use a fixed-size pool. `media-extractor.max-in-flight` bounds pending and active extraction work so scanning large backups does not create an unbounded task queue. `media-extractor.output-directory` configures the default base output directory (defaults to `~/memories` if blank). `media-extractor.quarantine-enabled` controls whether corrupted files are safely isolated in `{output}/quarantine/` (default `true`). `media-extractor.preserve-timestamps` sets the destination file's filesystem modified time to the extracted capture date (default `true`). Progress is logged periodically and includes scanned, completed, in-flight, extracted, duplicate, corrupt, quarantined, failed, and rate metrics.

Reports are named `media-extraction-report-<timestamp>.json` and `.html`. They include source/output paths, timing, throughput, bytes written, peak in-flight work, per-type/year extraction counts, and item-level duplicate/corruption/failure details. Set `media-extractor.report-directory` to place them elsewhere.

### Examples

**1. Full Pipeline (Extract + Java Triage + Python AI Classification + Quarantine):**
```bash
# Recommended runner (builds JAR and virtual environment automatically)
./scripts/run_pipeline.sh /path/to/backup

# Or using the built JAR directly:
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar /path/to/backup
```

**2. Dry-Run Audit Mode (Classify & log without moving files):**
```bash
./scripts/run_pipeline.sh /path/to/backup --dry-run
```

**3. Ultra-Fast Java-Only Triage (Header/EXIF certification without spawning Python):**
```bash
./scripts/run_pipeline.sh /path/to/backup --mode=java-only
```

**4. Pure Extraction Only (Skip AI classification sidecar):**
```bash
./scripts/run_pipeline.sh /path/to/backup --no-classify
```

**5. In-Place Organization (Route memes/greetings without quarantine directory):**
```bash
./scripts/run_pipeline.sh /path/to/backup --no-quarantine
```

**6. Force Full Re-evaluation (Disable resume and re-evaluate all images):**
```bash
./scripts/run_pipeline.sh /path/to/backup --no-resume
```

**7. Custom SQLite Progress-State Database:**
```bash
./scripts/run_pipeline.sh /path/to/backup --state-db=/custom/path/classification.sqlite3
```

**8. Audit & Sanitize Existing Memories (Quarantine corrupt files, prune duplicates):**
```bash
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar --sanitize
```

**9. Classify Only (Classify existing extracted media without re-extracting):**
```bash
# In default output directory (~/memories):
./scripts/run_pipeline.sh --classify
# Or with pre-built JAR:
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar --classify

# In custom output directory:
./scripts/run_pipeline.sh --classify /path/to/output
# Or with pre-built JAR:
java -jar target/media-extractor-0.0.1-SNAPSHOT.jar --classify /path/to/output
```

**10. Windows PowerShell / CMD:**
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

The Python classifier is enabled by default to run automatically after extraction with Java-assisted triage.
Its behavior is configured in `src/main/resources/application.properties` or overridden via CLI flags:

```properties
media-extractor.classifier-enabled=true
media-extractor.classifier-mode=java-triage-python
media-extractor.classifier-max-concurrency=256
media-extractor.classifier-action=move
media-extractor.classifier-quarantine=true
media-extractor.classifier-python=.venv/bin/python
media-extractor.classifier-script=scripts/classify_memes.py
media-extractor.classifier-working-directory=.
```

**Classifier Modes:**
- `java-triage-python` (Default): Uses high-speed Java virtual threads to inspect image headers and certify unambiguous camera photos (< 0.5ms/image). Obvious photos stay in `~/memories/{YYYY}/photos/` and bypass Python models completely. Candidates (screenshots, memes, non-camera images) are staged to `~/memories/.staging-{runId}/` and passed to Python via an enriched JSON Lines manifest. Guaranteed rollback restores any stranded files if interrupted.
- `python`: Legacy execution passing all extracted images directly to Python via manifest without Java triage.
- `java-only`: Experimental mode performing only Java-level camera triage without spawning the Python process.
- `disabled`: Skips classification entirely.

**CLI Overrides:**
- `--mode=java-triage-python`, `--mode=python`, `--mode=java-only`, `--mode=disabled`
- `--classify`, `--no-classify`
- `--move`, `--copy`, `--dry-run`
- `--quarantine`, `--no-quarantine`
- `--no-resume` (forces full re-evaluation without skipping previously completed items)
- `--state-db=<path>` (sets custom SQLite progress-state database path)

- By default, action is `move` and quarantine is `true`, routing memes and greetings directly into `~/memories/quarantine/{YYYY}/`.
- Use `--dry-run` to inspect without moving, or `--no-classify` to disable the classifier.
- When running via `./scripts/run_pipeline.sh`, all flags are accepted and forwarded automatically.
- Automatic classification is skipped for `--sanitize`/`--clean`, the `test` profile, and runs that extract no photos. Python startup failures, nonzero exit codes, and timeouts are logged while the completed Java extraction remains successful. Staged files are automatically restored on failure.

### Crash-Safe, Resumable Progress-State System

Classification progress is authoritatively managed in a durable SQLite database (default: `~/memories/.classifier-state/classification.sqlite3`).

**Key Reliability Features:**
- **Authoritative SQLite State**: Uses WAL journal mode, busy timeouts, and atomic multi-statement transactions.
- **Two-Phase Commit**: Classification decisions and intended destinations are durably persisted *before* filesystem routing occurs. Interrupted moves are reconciled automatically on restart.
- **Cheap Fingerprinting**: Files are identified via canonical path, size, nanosecond modification time, and sparse head/tail SHA-256 hash. Modified files are detected and reprocessed; unchanged completed files are skipped.
- **Single-Process Lock & Lease Management**: Prevents concurrent classifier runs on the same database. Stale runs (dead PIDs or expired heartbeat leases) are safely claimed and in-flight items recovered.
- **Orphan Staging Recovery**: Java triage scans for abandoned `.staging-*` directories on startup, reconciling staged files with manifests and safely restoring them without overwriting user data.
- **Graceful Cancellation**: Handles `SIGINT` (Ctrl+C) and `SIGTERM`, stopping cleanly after the active batch, checkpointing progress, and flushing metadata.
- **Report Consistency**: `classification_results.csv` and `review_queue.csv` are synced and fsynced atomically with no duplicate rows during resumes.

### Incremental Extraction & 7-Zip Backup Mode

When you have a baseline backup of `~/archive/memories` stored on an external drive, re-compressing hundreds of gigabytes for every new SD card ingestion is slow and causes excessive drive wear.

The `--incremental` flag enables **incremental extraction and packaging**:
1. **Deduplication**: Scans incoming source directories and copies only brand new photos and videos into `~/archive/memories/{YYYY}/`.
2. **Classification**: New photos undergo camera triage and AI classification; memes and greetings are relocated to `~/archive/memories/quarantine/{YYYY}/`.
3. **Packaging**: Collects only the newly extracted media files, quarantined items, run extraction reports (`media-extraction-report-*.json/html`), and updated classification CSVs.
4. **Split 7-Zip Archive**: Invokes native `/usr/bin/7z` with multithreading (`-mmt=on`), fast compression (`-mx=1`), and volume splitting (default: `-v4g`, compatible with FAT32/exFAT drives).
5. **Integrity Test**: Automatically runs `7z t` to verify archive integrity before finishing.
6. **Local Staging**: Part files (`.7z.001`, `.7z.002`, etc.) and a JSON receipt are generated locally in `<output>/backups` (default: `~/archive/memories/backups`) ready for manual transfer to your external drive.

**Usage Examples:**

```bash
# Ingest new camera SD card with incremental 4GB split backup
./scripts/run_pipeline.sh /media/sdcard/DCIM --incremental

# Specify custom backup folder (e.g. directly on a mounted external drive)
./scripts/run_pipeline.sh /media/sdcard/DCIM --incremental --backup-dir=/mnt/external_backup/incremental

# Custom part size and compression
./scripts/run_pipeline.sh /media/sdcard/DCIM --incremental --backup-part-size=2g --backup-compression=fast
```

**Disaster Recovery Restoration:**

To restore files onto a fresh system:
```bash
# 1. Restore the baseline backup
7z x memories_base.7z.001 -o~/archive/memories/

# 2. Overlay incremental backup updates in chronological order
7z x memories_incremental_20260912_150328.7z.001 -aoa -o~/archive/memories/
```
The relative directory structure (`{YYYY}/photos/`, `{YYYY}/videos/`, `quarantine/`, `reports/`) matches seamlessly.

