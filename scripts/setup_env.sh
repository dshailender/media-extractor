#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
VENV_DIR="$ROOT_DIR/.venv"

echo "=== Media Extractor: Meme Classifier Environment Setup ==="
echo "Project root: $ROOT_DIR"
echo "Virtual environment: $VENV_DIR"

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating virtual environment using $(python3 --version)..."
    python3 -m venv "$VENV_DIR"
else
    echo "Virtual environment already exists at $VENV_DIR"
fi

# Activate venv
source "$VENV_DIR/bin/activate"

echo "Upgrading pip..."
pip install --upgrade pip

echo "Installing core dependencies (Pillow, piexif, tqdm, pandas, opencv-python-headless, google-genai, easyocr)..."
pip install pillow piexif tqdm pandas opencv-python-headless google-genai easyocr

echo "Installing PyTorch and Transformers for local Zero-Shot classification..."
pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu || pip install torch torchvision || true
pip install transformers || true

echo "=== Environment setup complete ==="
"$VENV_DIR/bin/python" -c "
import sys
print(f'Python: {sys.version}')
for pkg in ['PIL', 'piexif', 'tqdm', 'pandas', 'cv2', 'easyocr', 'google.genai', 'torch', 'transformers']:
    try:
        __import__(pkg)
        print(f'  [OK] {pkg}')
    except ImportError as e:
        print(f'  [MISSING] {pkg}: {e}')
"

