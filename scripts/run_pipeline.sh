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

# 3. Source directory argument
SOURCE_DIR="$1"
if [ -z "${SOURCE_DIR}" ]; then
    echo "Usage: $0 <source_directory> [options]"
    echo ""
    echo "Options:"
    echo "  --mode=<mode>     Classifier mode: java-triage-python (default), python, java-only, disabled"
    echo "  --dry-run         Run classifier in audit mode without moving memes/greetings"
    echo "  --no-classify     Skip Python classification entirely"
    echo "  --no-quarantine   Move memes/greetings to ~/memories/{YYYY}/ instead of quarantine"
    echo ""
    echo "Example:"
    echo "  $0 /path/to/backup"
    echo "  $0 /path/to/backup --dry-run"
    echo "  $0 /path/to/backup --mode=java-triage-python"
    echo "  $0 /path/to/backup --mode=python"
    exit 1
fi

shift

echo "=========================================================="
echo "Media Extractor: Unified Extraction & Classification"
echo "Source:          ${SOURCE_DIR}"
echo "Mode:            java-triage-python (default)"
echo "Action:          move (default)"
echo "Quarantine:      ~/memories/quarantine/{YYYY}/ (default)"
echo "=========================================================="

java -jar "${JAR_FILE}" "${SOURCE_DIR}" "$@"

