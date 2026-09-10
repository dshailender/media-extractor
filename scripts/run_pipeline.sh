#!/usr/bin/env bash
set -e

# Media Extractor: Unified Media Extraction & Multi-Modal Classification Runner
# Extracts photos/videos to ~/memories/{YYYY}/, then classifies photos and moves
# memes and greetings to ~/memories/quarantine/{YYYY}/.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${PROJECT_ROOT}"

# 1. Check Python virtual environment
if [ ! -d ".venv" ]; then
    echo "[WARN] Python virtual environment (.venv) not found."
    echo "[INFO] Setting up virtual environment via ./scripts/setup_env.sh..."
    bash ./scripts/setup_env.sh
fi

# 2. Check if jar is built
JAR_FILE="target/media-extractor-0.0.1-SNAPSHOT.jar"
if [ ! -f "${JAR_FILE}" ]; then
    echo "[INFO] Building media-extractor JAR..."
    ./mvnw package -DskipTests
fi

# 3. Source and Output directory arguments and options
SOURCE_DIR=""
OUTPUT_DIR=""
CLASSIFY_ONLY=false

if [ $# -gt 0 ] && [[ "$1" != -* ]]; then
    SOURCE_DIR="$1"
    shift
fi

if [ $# -gt 0 ] && [[ "$1" != -* ]]; then
    OUTPUT_DIR="$1"
    shift
fi

if [ -z "${SOURCE_DIR}" ] && [ $# -eq 0 ] && [ -z "${OUTPUT_DIR}" ]; then
    echo "Usage: $0 [source_directory] [output_directory] [options]"
    echo ""
    echo "Options:"
    echo "  -o, --output=<dir> Output base directory (default: ~/memories)"
    echo "  --output-dir=<dir> Alias for --output"
    echo "  --classify        Only classify extracted files in the output directory (skips extraction)"
    echo "  --resume          Resume classification on existing memories directory"
    echo "  --mode=<mode>     Classifier mode: java-triage-python (default), python, java-only, disabled"
    echo "  --dry-run         Run classifier in audit mode without moving memes/greetings"
    echo "  --no-classify     Skip Python classification entirely"
    echo "  --no-quarantine   Move memes/greetings directly to {output}/{YYYY}/ instead of quarantine"
    echo "  --no-resume       Disable resume and reprocess all files"
    echo "  --state-db=<path> Custom SQLite state database location"
    echo ""
    echo "Examples:"
    echo "  $0 /path/to/backup"
    echo "  $0 /path/to/backup /path/to/output"
    echo "  $0 /path/to/backup --output=/path/to/output"
    echo "  $0 /path/to/backup -o ~/my_memories --dry-run"
    echo "  $0 --classify"
    echo "  $0 --classify /path/to/output"
    echo "  $0 /path/to/output --classify"
    echo "  $0 --resume --output=/path/to/output"
    echo "  $0 --dry-run"
    echo "  $0 /path/to/backup --mode=java-triage-python"
    exit 1
fi

EXTRA_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --classify|--classify-only|--resume)
            CLASSIFY_ONLY=true
            EXTRA_ARGS+=("$1")
            shift
            ;;
        --output=*)
            OUTPUT_DIR="${1#*=}"
            EXTRA_ARGS+=("$1")
            shift
            ;;
        --output)
            OUTPUT_DIR="$2"
            EXTRA_ARGS+=("$1" "$2")
            shift 2
            ;;
        --output-dir=*)
            OUTPUT_DIR="${1#*=}"
            EXTRA_ARGS+=("$1")
            shift
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            EXTRA_ARGS+=("$1" "$2")
            shift 2
            ;;
        -o=*)
            OUTPUT_DIR="${1#*=}"
            EXTRA_ARGS+=("$1")
            shift
            ;;
        -o)
            OUTPUT_DIR="$2"
            EXTRA_ARGS+=("$1" "$2")
            shift 2
            ;;
        --no-resume)
            EXTRA_ARGS+=("--media-extractor.classifier-resume=false")
            shift
            ;;
        --state-db=*)
            EXTRA_ARGS+=("--media-extractor.classifier-state-db=${1#*=}")
            shift
            ;;
        --state-db)
            EXTRA_ARGS+=("--media-extractor.classifier-state-db=$2")
            shift 2
            ;;
        *)
            EXTRA_ARGS+=("$1")
            shift
            ;;
    esac
done

if [ "${CLASSIFY_ONLY}" = true ]; then
    if [ -z "${OUTPUT_DIR}" ] && [ -n "${SOURCE_DIR}" ]; then
        OUTPUT_DIR="${SOURCE_DIR}"
    fi
    SOURCE_DIR=""
fi

RESOLVED_OUTPUT="${OUTPUT_DIR:-~/memories}"

echo "=========================================================="
echo "Media Extractor: Unified Extraction & Classification"
if [ -n "${SOURCE_DIR}" ]; then
    echo "Source:          ${SOURCE_DIR}"
else
    echo "Source:          [Existing ${RESOLVED_OUTPUT}/ (Classifier-only)]"
fi
echo "Output:          ${RESOLVED_OUTPUT} (base directory)"
echo "Mode:            java-triage-python (default)"
echo "Action:          move (default)"
echo "Quarantine:      ${RESOLVED_OUTPUT}/quarantine/{YYYY}/ (default)"
echo "=========================================================="

CMD=(java -jar "${JAR_FILE}")
if [ -n "${SOURCE_DIR}" ]; then
    CMD+=("${SOURCE_DIR}")
fi
if [ -n "${OUTPUT_DIR}" ]; then
    if [[ ! " ${EXTRA_ARGS[*]} " =~ " --output" ]] && [[ ! " ${EXTRA_ARGS[*]} " =~ " -o" ]] && [[ ! " ${EXTRA_ARGS[*]} " =~ " --output-dir" ]]; then
        CMD+=("${OUTPUT_DIR}")
    fi
fi
CMD+=("${EXTRA_ARGS[@]}")

"${CMD[@]}"


