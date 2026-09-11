#!/usr/bin/env python3
"""
scripts/classify_memes.py

High-accuracy, multi-modal local-first image classification pipeline for media-extractor:
1. Tier 1: EXIF Camera Hardware Check & Orientation Transpose
2. Tier 2: Local Preprocessed Multi-Variant OCR (EasyOCR on CPU) with Keyword & Structural Signals
3. Tier 3: Local Multi-Class Prompt Ensembling (CLIP Vision Transformer)
4. Tier 4: Conservative Hierarchical Decision Engine with Conflict & Uncertainty Tracking
5. Tier 5: Structured Gemini Free-Tier Fallback (rate-limited, with OCR context & strict JSON)

Supports:
- Review Mode: Low-confidence/conflicting items routed to review queue CSV and protected from moves.
- Evaluation Mode: Benchmarking against labeled CSVs with precision/recall/F1 and confusion matrix.
- Quarantine & Rescue Modes: Full routing backward compatibility with media-extractor.
"""

import argparse
import csv
import hashlib
import json
import math
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import unicodedata
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Generator, List, Optional, Set, Tuple

sys.path.insert(0, str(Path(__file__).parent))
from classifier_state import (
    ClassifierLockError,
    ClassifierStateStore,
    LifecycleStatus,
    compute_file_fingerprint,
    compute_item_id,
    estimate_processing_time,
    format_duration,
)

import cv2
import numpy as np
from PIL import Image, ImageOps, ImageFilter, ExifTags, ImageFile
ImageFile.LOAD_TRUNCATED_IMAGES = True
from tqdm import tqdm

import torch

# Optimize PyTorch CPU threading for physical cores on Zen 3+ (Ryzen 5 6600H)
try:
    torch.set_num_threads(min(6, os.cpu_count() or 6))
except Exception:
    pass

MODEL_VERSION = "2.1.0-fast-hierarchical-ocr-clip"

# Camera EXIF tag identifiers
CAMERA_MAKE_TAG = 0x010F   # 'Make'
CAMERA_MODEL_TAG = 0x0110  # 'Model'
LENS_MODEL_TAG = 0xA434   # 'LensModel'
FOCAL_LENGTH_TAG = 0x920A # 'FocalLength'

SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tiff", ".tif"}

CSV_HEADER = [
    "file_path",
    "category",
    "confidence",
    "tier",
    "decision_reason",
    "ocr_text",
    "ocr_confidence",
    "text_box_count",
    "text_area_ratio",
    "clip_top_label",
    "clip_margin",
    "greeting_keyword_hits",
    "meme_keyword_hits",
    "uncertainty",
    "model_version",
    "relocated_to",
    "timestamp",
]


class TokenBucketRateLimiter:
    """Thread-safe / monotonic token-bucket rate limiter tailored for Gemini Free Tier (15 RPM)."""
    def __init__(self, rpm: float = 12.0):
        self.rpm = rpm
        self.min_interval = 60.0 / max(rpm, 1.0)
        self.last_call_time = 0.0

    def wait(self):
        now = time.monotonic()
        elapsed = now - self.last_call_time
        if elapsed < self.min_interval:
            sleep_duration = self.min_interval - elapsed
            time.sleep(sleep_duration)
        self.last_call_time = time.monotonic()


class ExifCameraFilter:
    """Fast EXIF inspector to identify camera photos in 0ms."""
    @staticmethod
    def is_camera_photo(image_path: Path, pil_image: Optional[Image.Image] = None) -> Tuple[bool, Optional[str]]:
        try:
            exif_data = None
            if pil_image is not None and hasattr(pil_image, "getexif"):
                exif_data = pil_image.getexif()
            if not exif_data:
                with Image.open(image_path) as img:
                    exif_data = img.getexif()

            if not exif_data:
                return False, None

            make = exif_data.get(CAMERA_MAKE_TAG)
            model = exif_data.get(CAMERA_MODEL_TAG)
            lens = exif_data.get(LENS_MODEL_TAG)

            if make or model:
                device_str = f"{str(make or '').strip()} {str(model or '').strip()}".strip()
                return True, device_str or "Camera Device"
            if lens:
                return True, f"Lens: {lens}".strip()
        except Exception:
            return False, None

        return False, None


class ImagePreprocessor:
    """OpenCV and Pillow preprocessing pipeline for robust OCR text extraction."""
    @staticmethod
    def correct_orientation(pil_image: Image.Image) -> Image.Image:
        """Applies EXIF orientation transpose to ensure right-side-up image."""
        try:
            return ImageOps.exif_transpose(pil_image) or pil_image
        except Exception:
            return pil_image

    @staticmethod
    def safe_convert_rgb(pil_image: Image.Image) -> Image.Image:
        """Converts any PIL image mode (RGBA, CMYK, P, L, 1) safely to RGB."""
        if pil_image.mode == "RGB":
            return pil_image
        if pil_image.mode == "RGBA":
            bg = Image.new("RGB", pil_image.size, (255, 255, 255))
            bg.paste(pil_image, mask=pil_image.split()[3])
            return bg
        return pil_image.convert("RGB")

    @staticmethod
    def load_rgb_image(image_path: Path, max_dim: int = 1600) -> Image.Image:
        """
        Loads an image file, applies EXIF orientation transpose, converts to RGB,
        and bounds maximum dimension to max_dim to avoid excessive CPU/memory load.
        Guarantees that the image pixel buffer is fully loaded in memory and detached
        from any underlying file stream.
        """
        with Image.open(image_path) as raw_img:
            w, h = raw_img.size
            largest = max(w, h)
            if largest > max_dim:
                scale = max_dim / float(largest)
                target_size = (int(w * scale), int(h * scale))
                try:
                    raw_img.draft("RGB", target_size)
                except Exception:
                    pass
            raw_img.load()
            oriented = ImagePreprocessor.correct_orientation(raw_img)
            rgb = ImagePreprocessor.safe_convert_rgb(oriented)
            if max(rgb.size) > max_dim:
                scale = max_dim / float(max(rgb.size))
                new_w = max(1, int(rgb.size[0] * scale))
                new_h = max(1, int(rgb.size[1] * scale))
                rgb = rgb.resize((new_w, new_h), Image.Resampling.BILINEAR)
            else:
                rgb.load()
                if rgb is raw_img or getattr(rgb, "fp", None) is not None:
                    rgb = rgb.copy()
            return rgb

    @classmethod
    def generate_ocr_variants(cls, pil_rgb: Image.Image) -> Generator[Tuple[str, np.ndarray], None, None]:
        """
        Generates preprocessing variants for OCR on demand:
        - original: standard RGB to BGR
        - enlarged: bicubic upscaling if resolution is low
        - high_contrast_gray: CLAHE contrast normalization
        - adaptive_thresh: Gaussian adaptive thresholding
        - sharpened: unsharp convolution filter
        """
        bgr = cv2.cvtColor(np.array(pil_rgb), cv2.COLOR_RGB2BGR)
        yield ("original", bgr)

        h, w = bgr.shape[:2]
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)

        # 1. Enlarged (for small images or text)
        if min(h, w) < 800:
            scale = min(2.0, 800.0 / max(min(h, w), 1))
            new_w, new_h = int(w * scale), int(h * scale)
            enlarged = cv2.resize(bgr, (new_w, new_h), interpolation=cv2.INTER_CUBIC)
            yield ("enlarged", enlarged)

        # 2. High contrast grayscale (CLAHE)
        try:
            clahe = cv2.createCLAHE(clipLimit=2.5, tileGridSize=(8, 8))
            contrast_gray = clahe.apply(gray)
            yield ("high_contrast_gray", cv2.cvtColor(contrast_gray, cv2.COLOR_GRAY2BGR))
        except Exception:
            pass

        # 3. Adaptive thresholding
        try:
            adaptive = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 15, 4)
            yield ("adaptive_thresh", cv2.cvtColor(adaptive, cv2.COLOR_GRAY2BGR))
        except Exception:
            pass

        # 4. Sharpened
        try:
            kernel = np.array([[0, -1, 0], [-1, 5, -1], [0, -1, 0]], dtype=np.float32)
            sharpened = cv2.filter2D(bgr, -1, kernel)
            yield ("sharpened", sharpened)
        except Exception:
            pass


class EasyOcrEngine:
    """Lazy-loaded EasyOCR reader on CPU with multi-variant extraction, deduplication, and caching."""
    def __init__(self, languages: Optional[List[str]] = None, gpu: bool = False):
        self.languages = languages or ["en"]
        self.gpu = gpu
        self._reader = None
        self._cache: Dict[Tuple[str, int, float], Dict[str, Any]] = {}

    def _load_reader(self):
        if self._reader is None:
            cache_dir = Path(__file__).resolve().parent.parent / ".cache" / "easyocr"
            cache_dir.mkdir(parents=True, exist_ok=True)
            print(f"[INFO] Initializing EasyOCR reader (languages={self.languages}, gpu={self.gpu}, cache={cache_dir})...")
            import easyocr
            self._reader = easyocr.Reader(
                self.languages,
                gpu=self.gpu,
                model_storage_directory=str(cache_dir),
                user_network_directory=str(cache_dir),
                verbose=False,
            )
            print("[INFO] EasyOCR reader ready.")

    def extract_features(
        self,
        image_path: Path,
        pil_image: Image.Image,
        has_camera_exif: bool = False,
        run_variants: bool = True,
    ) -> Dict[str, Any]:
        """
        Extracts OCR text, bounding boxes, confidence, text area ratio, and layout positions.
        Caches results by (path, file_size, mtime) to prevent recomputation.
        Avoids excessive preprocessing on natural camera photos.
        """
        try:
            stat = image_path.stat()
            cache_key = (str(image_path.resolve()), stat.st_size, stat.st_mtime)
            if cache_key in self._cache:
                return self._cache[cache_key]
        except Exception:
            cache_key = None

        if pil_image.mode == "RGB":
            norm_rgb = pil_image
        else:
            norm_image = ImagePreprocessor.correct_orientation(pil_image)
            norm_rgb = ImagePreprocessor.safe_convert_rgb(norm_image)
        orig_w, orig_h = norm_rgb.size
        img_area = float(max(orig_w * orig_h, 1))

        default_result = {
            "ocr_text": "",
            "ocr_confidence": 0.0,
            "text_box_count": 0,
            "text_area_ratio": 0.0,
            "boxes": [],
            "top_band_count": 0,
            "bottom_band_count": 0,
        }

        try:
            self._load_reader()
        except Exception as e:
            print(f"[WARN] EasyOCR initialization failed: {e}")
            return default_result

        # Camera photos do not require multi-variant enhancement sweeps
        if has_camera_exif:
            run_variants = False

        # Scale down for OCR if image is large: 800px max dimension provides 2.5x faster
        # CRAFT text detection while capturing all meme captions, greetings, and screenshots.
        try:
            ocr_scale = 1.0
            max_dim = max(orig_w, orig_h)
            if max_dim > 800:
                ocr_scale = 800.0 / max_dim
                norm_rgb_for_ocr = norm_rgb.resize((int(orig_w * ocr_scale), int(orig_h * ocr_scale)), Image.Resampling.BILINEAR)
            else:
                norm_rgb_for_ocr = norm_rgb
        except Exception as e:
            print(f"[WARN] Failed resizing image for OCR ({image_path}): {repr(e)}")
            norm_rgb_for_ocr = norm_rgb

        try:
            variants = ImagePreprocessor.generate_ocr_variants(norm_rgb_for_ocr)
        except Exception as e:
            print(f"[WARN] Failed generating OCR variants ({image_path}): {repr(e)}")
            return default_result
        all_detections: List[Tuple[List, str, float]] = []
        seen_texts: Set[str] = set()

        with torch.inference_mode():
            for var_idx, (var_name, var_bgr) in enumerate(variants):
                if var_idx > 0 and not run_variants:
                    break

                try:
                    raw_results = self._reader.readtext(var_bgr, detail=1, paragraph=False)
                    for bbox, text, conf in raw_results:
                        clean_str = text.strip()
                        if not clean_str or len(clean_str) < 2 or conf < 0.20:
                            continue

                        norm_key = re.sub(r"\W+", "", clean_str).lower()
                        if norm_key in seen_texts:
                            continue
                        seen_texts.add(norm_key)

                        # Scale bbox back to original image dimensions if downscaled
                        if ocr_scale != 1.0:
                            scaled_bbox = [[pt[0] / ocr_scale, pt[1] / ocr_scale] for pt in bbox]
                        else:
                            scaled_bbox = bbox

                        all_detections.append((scaled_bbox, clean_str, float(conf)))

                    # Smart short-circuit:
                    # A. Zero text detected on original: skip remaining variants (avoid excessive preprocessing on natural photos)
                    if var_idx == 0 and len(all_detections) == 0:
                        break

                    # B. Any image with clear confident text: skip remaining variants
                    if var_idx == 0:
                        joined = " ".join(t for _, t, _ in all_detections)
                        avg_c = np.mean([c for _, _, c in all_detections]) if all_detections else 0.0
                        if len(joined) >= 15 and avg_c >= 0.60:
                            break
                except Exception as e:
                    pass

        if not all_detections:
            if cache_key:
                self._cache[cache_key] = default_result
            return default_result

        # Calculate bounding box metrics relative to original image size
        total_box_area = 0.0
        top_band_count = 0
        bottom_band_count = 0
        boxes_out = []

        for bbox, text, conf in all_detections:
            try:
                xs = [p[0] for p in bbox]
                ys = [p[1] for p in bbox]
                box_w = max(xs) - min(xs)
                box_h = max(ys) - min(ys)
                center_y = (min(ys) + max(ys)) / 2.0
                area = float(box_w * box_h)
                total_box_area += area

                if center_y < 0.25 * orig_h:
                    top_band_count += 1
                elif center_y > 0.75 * orig_h:
                    bottom_band_count += 1

                boxes_out.append({
                    "text": text,
                    "confidence": conf,
                    "area": area,
                    "center_y": center_y,
                })
            except Exception:
                pass

        combined_text = " ".join(t for _, t, _ in all_detections)
        avg_confidence = float(np.mean([c for _, _, c in all_detections])) if all_detections else 0.0
        text_area_ratio = min(1.0, total_box_area / img_area)

        result = {
            "ocr_text": combined_text,
            "ocr_confidence": avg_confidence,
            "text_box_count": len(all_detections),
            "text_area_ratio": text_area_ratio,
            "boxes": boxes_out,
            "top_band_count": top_band_count,
            "bottom_band_count": bottom_band_count,
        }

        if cache_key:
            self._cache[cache_key] = result
        return result


class SignalExtractor:
    """Extracts explicit linguistic, layout, and geometric signals from image metadata and OCR text."""

    GREETING_PATTERNS = [
        # English daily wishes & greetings
        r"\bgood\s*morning\b", r"\bgood\s*night\b", r"\bgood\s*evening\b", r"\bgood\s*afternoon\b",
        r"\bhappy\s*birthday\b", r"\bhappy\s*anniversary\b", r"\bhappy\s*new\s*year\b",
        r"\bmerry\s*christmas\b", r"\bhappy\s*diwali\b", r"\bhappy\s*holi\b", r"\bhappy\s*eid\b",
        r"\bblessings\b", r"\bgod\s*bless\b", r"\bhave\s*a\s*(blessed|great|wonderful|good)\s*day\b",
        r"\bthought\s*for\s*the\s*day\b", r"\binspirational\s*quote\b", r"\bmotivational\s*quote\b",
        r"\bdaily\s*quote\b", r"\bquote\s*of\s*the\s*day\b", r"\bwishing\s*you\b", r"\bwish\s*(you|u)\b",
        r"\bwishes\b", r"\bpeace\s*and\s*joy\b", r"\bsweet\s*moments\b", r"\bcolou?rful\s*memories\b",
        r"\bcongratulations\b", r"\bcongrats\b", r"\bgreetings\b",
        # Festivals and special days
        r"\bdiwali\b", r"\bdeepawali\b", r"\bdeepavali\b", r"\bdhanteras\b",
        r"\bholi\b", r"\bnavratri\b", r"\bdurgapuja\b", r"\bdurga\s*puja\b",
        r"\bdussehra\b", r"\bvijayadashami\b", r"\bganesh\b", r"\bganpati\b",
        r"\bkrishna\b", r"\bjanmashtami\b", r"\bmakar\s*sankranti\b", r"\blohri\b",
        r"\bpongal\b", r"\bonam\b", r"\bbaisakhi\b", r"\bkarwa\s*chauth\b",
        r"\bmahashivratri\b", r"\bshivratri\b", r"\bram\s*navami\b",
        r"\bfriendship\s*day\b", r"\bteachers?('?s)?\s*day\b", r"\bchildrens?('?s)?\s*day\b",
        r"\bmothers?('?s)?\s*day\b", r"\bfathers?('?s)?\s*day\b",
        # Transliterated / Hindi / Indian Festival & Devotional phrases
        r"\bsuprabhat\b", r"\bshubh\s*prabhat\b", r"\bshubh\s*ratri\b", r"\bshubh\s*deepavali\b",
        r"\bshubh\s*navratri\b", r"\bshubh\b", r"\bkhayal\s*rakh(e|na)\b", r"\btake\s*care\b",
        r"\bramadan\s*mubarak\b", r"\beid\s*mubarak\b",
        r"\bradhe\s*radhe\b", r"\bjai\s*shri?\s*ram\b", r"\bom\s*sai\s*ram\b", r"\bjai\s*mata\s*di\b",
        r"\bhar\s*har\s*mahadev\b", r"\bjai\s*jinendra\b", r"\bnamaskar\b", r"\bnamaste\b", r"\bpranam\b",
    ]

    MEME_PATTERNS = [
        # Catchphrases & Meme templates
        r"\bwhen\s*you\b", r"\bthat\s*moment\s*when\b", r"\bnobody:\b", r"\bpov:\b",
        r"\bme:\b", r"\bmy\s*face\s*when\b", r"\bmfw\b", r"\brelatable\b", r"\bbruh\b",
        r"\bdank\b", r"\blmao\b", r"\brofl\b", r"\blol\b", r"\bmeme\b", r"\bshitpost\b",
        r"\badmin\b", r"\btag\s*(someone|your|a\s*friend)\b", r"\bshare\s*this\b",
        r"\bforwarded\s*as\s*received\b", r"\bwhatsapp\s*forward\b", r"\blike\s*and\s*subscribe\b",
        r"\bcomment\s*below\b", r"\bretweet\b", r"\bswipe\s*left\b", r"\bviral\b",
        # Social media platforms & chat UI elements
        r"\bwhatsapp\b", r"\bfacebook\b", r"\btwitter\b", r"\binstagram\b", r"\breddit\b",
        r"\bsnapchat\b", r"\btelegram\b", r"\btiktok\b", r"\btyping\.\.\.\b",
    ]

    DOCUMENT_PATTERNS = [
        r"\binvoice\b", r"\breceipt\b", r"\btax\s*invoice\b", r"\bstatement\b", r"\bbill\b",
        r"\btotal\s*amount\b", r"\bgrand\s*total\b", r"\bsubtotal\b", r"\bdate:\b", r"\bsignature\b",
        r"\baccount\s*no\b", r"\border\s*id\b", r"\border\s*no\b", r"\bpan\s*card\b", r"\baadhaar\b",
        r"\bpassport\b", r"\bdriving\s*licen[cs]e\b", r"\bcheque\b", r"\bbank\b", r"\bcertificate\b",
        r"\bterms\s*and\s*conditions\b",
    ]

    def __init__(self):
        self.compiled_greeting = [re.compile(p, re.IGNORECASE | re.UNICODE) for p in self.GREETING_PATTERNS]
        self.compiled_meme = [re.compile(p, re.IGNORECASE | re.UNICODE) for p in self.MEME_PATTERNS]
        self.compiled_document = [re.compile(p, re.IGNORECASE | re.UNICODE) for p in self.DOCUMENT_PATTERNS]

    def extract_signals(self, pil_image: Image.Image, ocr_data: Dict[str, Any], has_camera_exif: bool) -> Dict[str, Any]:
        text = unicodedata.normalize("NFKD", ocr_data.get("ocr_text", ""))

        greeting_hits = [p.pattern for p in self.compiled_greeting if p.search(text)]
        meme_hits = [p.pattern for p in self.compiled_meme if p.search(text)]
        document_hits = [p.pattern for p in self.compiled_document if p.search(text)]

        w, h = pil_image.size
        aspect_ratio = float(w) / float(max(h, 1))

        # Check for typical mobile smartphone screenshot resolutions/ratios
        is_screenshot_ratio = (
            (0.45 <= aspect_ratio <= 0.60) or  # Portrait smartphone (9:16 ~ 0.56, 9:20 ~ 0.45)
            (1.65 <= aspect_ratio <= 2.25)     # Landscape smartphone
        )
        standard_screen_widths = {720, 1080, 1440, 800, 1200, 1600}
        is_exact_screen_res = (w in standard_screen_widths or h in standard_screen_widths)
        is_likely_screenshot = (is_screenshot_ratio and is_exact_screen_res and not has_camera_exif)

        # Check for solid color border indicators (common in memes and framed greeting cards)
        has_uniform_border = False
        try:
            arr = np.array(pil_image.resize((100, 100)))
            top_strip = arr[:4, :, :]
            bottom_strip = arr[-4:, :, :]
            top_std = float(np.std(top_strip))
            bottom_std = float(np.std(bottom_strip))
            if top_std < 12.0 and bottom_std < 12.0:
                has_uniform_border = True
        except Exception:
            pass

        return {
            "greeting_keyword_hits": greeting_hits,
            "meme_keyword_hits": meme_hits,
            "document_keyword_hits": document_hits,
            "aspect_ratio": aspect_ratio,
            "is_likely_screenshot": is_likely_screenshot,
            "has_uniform_border": has_uniform_border,
            "has_caption_layout": (ocr_data.get("top_band_count", 0) > 0 and ocr_data.get("bottom_band_count", 0) > 0),
        }


class LocalClipEnsembleClassifier:
    """Local multi-class Zero-Shot Vision Classifier using prompt ensembling on CLIP."""
    def __init__(self, device: str = "cpu"):
        self.device = device
        self._model = None
        self._processor = None
        self._text_weights = None

        # Diverse multi-prompt ensembles per category
        self.category_prompts = {
            "PHOTO": [
                "a clear natural photograph of people, friends, family portrait, or landscape",
                "a casual candid snapshot, travel photo, pet, or natural scene",
                "a printed paper document, invoice, receipt, or book page",
            ],
            "MEME": [
                "an internet meme with a bold humorous text caption, joke, or funny commentary",
                "a screenshot of a social media chat conversation, post, or smartphone screen",
            ],
            "GREETING": [
                "a colorful greeting card with a Good Morning wish, festival greeting, or celebration banner",
                "an inspirational quote banner, religious blessing graphic, or birthday wish card",
            ],
        }

        # Flattened list for zero-shot classifier
        self.all_prompts: List[str] = []
        self.prompt_to_category: Dict[str, str] = {}
        for cat, prompts in self.category_prompts.items():
            for p in prompts:
                self.all_prompts.append(p)
                self.prompt_to_category[p] = cat

    def _load_model(self):
        if self._model is None:
            print("[INFO] Initializing local CLIP model (openai/clip-vit-base-patch32)...")
            os.environ.setdefault("HF_HUB_OFFLINE", "1")
            os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
            from transformers import CLIPModel, CLIPProcessor
            try:
                self._model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
                self._processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
            except Exception:
                os.environ.pop("HF_HUB_OFFLINE", None)
                os.environ.pop("TRANSFORMERS_OFFLINE", None)
                self._model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
                self._processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
            self._model.eval()
            if self.device != "cpu":
                try:
                    self._model.to(self.device)
                except Exception:
                    self.device = "cpu"
                    self._model.to("cpu")

            # Precompute static normalized prompt embeddings once
            with torch.inference_mode():
                text_inputs = self._processor(text=self.all_prompts, return_tensors="pt", padding=True)
                if self.device != "cpu":
                    text_inputs = {k: v.to(self.device) for k, v in text_inputs.items()}
                text_output = self._model.get_text_features(**text_inputs)
                text_embeds = text_output.pooler_output if hasattr(text_output, "pooler_output") else (text_output[0] if isinstance(text_output, tuple) else text_output)
                self._text_weights = text_embeds / text_embeds.norm(p=2, dim=-1, keepdim=True)
            print("[INFO] CLIP model ready with precomputed prompt embeddings.")

    def classify_batch(self, pil_images: List[Image.Image]) -> List[Dict[str, Any]]:
        """
        Runs prompt-ensembled CLIP inference with precomputed text weights.
        Returns for each image: top category, margin, normalized entropy, and aggregated scores.
        """
        self._load_model()
        if not pil_images:
            return []

        try:
            with torch.inference_mode():
                img_inputs = self._processor(images=pil_images, return_tensors="pt")
                if self.device != "cpu":
                    img_inputs = {k: v.to(self.device) for k, v in img_inputs.items()}
                img_output = self._model.get_image_features(**img_inputs)
                img_embeds = img_output.pooler_output if hasattr(img_output, "pooler_output") else (img_output[0] if isinstance(img_output, tuple) else img_output)
                img_embeds = img_embeds / img_embeds.norm(p=2, dim=-1, keepdim=True)
                logit_scale = self._model.logit_scale.exp()
                logits = torch.matmul(img_embeds, self._text_weights.t()) * logit_scale
                probs_matrix = logits.softmax(dim=-1).cpu().numpy()
        except Exception as e:
            print(f"[WARN] CLIP ensemble inference error: {repr(e)}")
            if len(pil_images) > 1:
                # Fall back to single-image inference so one bad image does not degrade entire batch
                fallback_results = []
                for single_img in pil_images:
                    fallback_results.extend(self.classify_batch([single_img]))
                return fallback_results
            return [{
                "top_label": "PHOTO",
                "confidence": 0.5,
                "margin": 0.0,
                "entropy": 1.0,
                "scores": {"PHOTO": 0.34, "MEME": 0.33, "GREETING": 0.33},
                "details": f"Inference error: {repr(e)}",
            } for _ in pil_images]

        output = []
        for row in probs_matrix:
            cat_sums = {"PHOTO": 0.0, "MEME": 0.0, "GREETING": 0.0}
            for idx, score in enumerate(row):
                label = self.all_prompts[idx]
                cat = self.prompt_to_category.get(label, "PHOTO")
                cat_sums[cat] += float(score)

            total = sum(cat_sums.values()) or 1.0
            cat_probs = {k: v / total for k, v in cat_sums.items()}

            sorted_cats = sorted(cat_probs.items(), key=lambda x: x[1], reverse=True)
            top_label, top_prob = sorted_cats[0]
            second_label, second_prob = sorted_cats[1]
            margin = top_prob - second_prob

            # Calculate normalized entropy H in [0, 1]
            entropy = 0.0
            num_classes = len(cat_probs)
            for p in cat_probs.values():
                if p > 1e-7:
                    entropy -= p * math.log(p)
            norm_entropy = min(1.0, entropy / math.log(num_classes))

            output.append({
                "top_label": top_label,
                "confidence": top_prob,
                "margin": margin,
                "entropy": norm_entropy,
                "scores": cat_probs,
                "details": f"CLIP {top_label} ({top_prob:.3f}, margin: {margin:.3f}, H: {norm_entropy:.2f})",
            })

        return output


class HierarchicalDecisionEngine:
    """Conservative hierarchical multi-modal decision engine fusing EXIF, OCR, Signals, and CLIP."""

    @staticmethod
    def decide(
        has_camera_exif: bool,
        camera_info: Optional[str],
        ocr_data: Dict[str, Any],
        signals: Dict[str, Any],
        clip_data: Dict[str, Any],
        review_threshold: float = 0.40,
    ) -> Dict[str, Any]:
        """
        Fuses all evidence hierarchically.
        Calculates category, confidence, decision reason, tier, uncertainty, and needs_review flag.
        """
        ocr_text = ocr_data.get("ocr_text", "")
        ocr_len = len(ocr_text)
        ocr_conf = ocr_data.get("ocr_confidence", 0.0)
        text_box_count = ocr_data.get("text_box_count", 0)
        text_area_ratio = ocr_data.get("text_area_ratio", 0.0)

        greeting_hits = signals.get("greeting_keyword_hits", [])
        meme_hits = signals.get("meme_keyword_hits", [])
        doc_hits = signals.get("document_keyword_hits", [])
        is_screenshot = signals.get("is_likely_screenshot", False)
        has_caption_layout = signals.get("has_caption_layout", False)

        clip_label = clip_data.get("top_label", "PHOTO")
        clip_conf = clip_data.get("confidence", 0.5)
        clip_margin = clip_data.get("margin", 0.0)
        clip_entropy = clip_data.get("entropy", 0.5)

        # Baseline uncertainty based on CLIP entropy and margin
        uncertainty = 0.5 * clip_entropy + 0.5 * max(0.0, 1.0 - clip_margin * 2.5)

        # RULE 1: Reliable Greeting OCR Signals
        if greeting_hits:
            confidence = min(0.99, max(0.85, 0.70 + 0.10 * len(greeting_hits) + 0.20 * ocr_conf))
            return {
                "category": "GREETING",
                "confidence": confidence,
                "tier": "OCR_SIGNAL",
                "decision_reason": f"Greeting keyword hit ({', '.join(greeting_hits[:2])})",
                "uncertainty": max(0.05, 0.25 - 0.05 * len(greeting_hits)),
                "needs_review": False,
            }

        # RULE 2: Reliable Meme OCR & Layout Signals
        if meme_hits and (ocr_conf >= 0.35 or is_screenshot or has_caption_layout):
            confidence = min(0.98, max(0.82, 0.65 + 0.15 * len(meme_hits) + 0.15 * ocr_conf))
            return {
                "category": "MEME",
                "confidence": confidence,
                "tier": "OCR_SIGNAL",
                "decision_reason": f"Meme keyword hit ({', '.join(meme_hits[:2])})",
                "uncertainty": max(0.05, 0.25 - 0.05 * len(meme_hits)),
                "needs_review": False,
            }

        # Caption layout with meme CLIP
        if has_caption_layout and clip_label == "MEME" and clip_conf >= 0.50:
            return {
                "category": "MEME",
                "confidence": clip_conf,
                "tier": "HEURISTIC",
                "decision_reason": "Top/bottom caption layout with meme visual indicators",
                "uncertainty": 0.25,
                "needs_review": False,
            }

        # RULE 3: Text-heavy Document Scan Protection
        if doc_hits:
            return {
                "category": "PHOTO",
                "confidence": 0.95,
                "tier": "OCR_SIGNAL",
                "decision_reason": f"Document / scan keyword hit ({', '.join(doc_hits[:2])})",
                "uncertainty": 0.05,
                "needs_review": False,
            }

        if (ocr_len >= 100 and text_box_count >= 6 and not greeting_hits and not meme_hits and clip_label != "GREETING"):
            return {
                "category": "PHOTO",
                "confidence": 0.90,
                "tier": "OCR_SIGNAL",
                "decision_reason": "Document / text scan without meme or greeting signals",
                "uncertainty": 0.15,
                "needs_review": False,
            }

        # RULE 4: Camera Hardware EXIF Prior (no meme/greeting text present)
        if has_camera_exif and not greeting_hits and not meme_hits:
            return {
                "category": "PHOTO",
                "confidence": 0.99,
                "tier": "EXIF_CAMERA",
                "decision_reason": f"Camera hardware metadata ({camera_info or 'EXIF'})",
                "uncertainty": 0.02,
                "needs_review": False,
            }

        # RULE 5: Natural Clean Photo (Zero or negligible OCR text)
        if ocr_len < 3:
            if clip_label == "PHOTO" or clip_margin < 0.25:
                return {
                    "category": "PHOTO",
                    "confidence": max(0.90, clip_conf),
                    "tier": "HEURISTIC",
                    "decision_reason": "Natural photograph (zero detected text)",
                    "uncertainty": 0.08,
                    "needs_review": False,
                }
            elif clip_label == "GREETING":
                if clip_conf >= 0.80 and clip_margin >= 0.50:
                    uncertainty = 0.42
                    return {
                        "category": "GREETING",
                        "confidence": clip_conf,
                        "tier": "CLIP_ENSEMBLE",
                        "decision_reason": "Greeting visual artwork / calligraphy without readable English OCR text",
                        "uncertainty": uncertainty,
                        "needs_review": (uncertainty >= review_threshold),
                    }
                return {
                    "category": "PHOTO",
                    "confidence": 0.85,
                    "tier": "HEURISTIC",
                    "decision_reason": "Protected photo (zero text detected despite greeting visual theme)",
                    "uncertainty": 0.30,
                    "needs_review": False,
                }
            elif clip_label == "MEME" and (is_screenshot or has_caption_layout):
                return {
                    "category": "MEME",
                    "confidence": clip_conf,
                    "tier": "CLIP_ENSEMBLE",
                    "decision_reason": "Meme visual layout / screenshot without clear OCR text",
                    "uncertainty": 0.35,
                    "needs_review": False,
                }
            else:
                return {
                    "category": "PHOTO",
                    "confidence": 0.80,
                    "tier": "HEURISTIC",
                    "decision_reason": "Protected photo (zero text overlay)",
                    "uncertainty": 0.30,
                    "needs_review": False,
                }

        # RULE 6: Disambiguation using CLIP Ensemble Evidence
        final_category = clip_label
        final_confidence = clip_conf
        final_tier = "CLIP_ENSEMBLE"
        decision_reason = f"CLIP Ensemble {clip_label} (margin: {clip_margin:.2f})"

        # Check for conflicting evidence
        if clip_label == "PHOTO" and (greeting_hits or meme_hits):
            uncertainty = 0.60
            decision_reason = "Conflicting: CLIP PHOTO but keywords detected"
        elif clip_label != "PHOTO" and ocr_len == 0:
            uncertainty = 0.50

        needs_review = (uncertainty >= review_threshold)

        return {
            "category": final_category,
            "confidence": final_confidence,
            "tier": final_tier,
            "decision_reason": decision_reason,
            "uncertainty": uncertainty,
            "needs_review": needs_review,
        }


def load_env_file(env_path: Optional[Path] = None):
    """Loads KEY=VALUE pairs from .env or .env.local into os.environ if not already present."""
    candidates = [
        env_path,
        Path.cwd() / ".env",
        Path.cwd() / ".env.local",
        Path(__file__).resolve().parent.parent / ".env",
        Path(__file__).resolve().parent.parent / ".env.local",
    ]
    for candidate in candidates:
        if candidate and candidate.is_file():
            try:
                with open(candidate, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line or line.startswith("#"):
                            continue
                        if "=" in line:
                            k, v = line.split("=", 1)
                            k = k.strip()
                            v = v.strip().strip("'\"")
                            if k and (k not in os.environ or not os.environ[k]):
                                os.environ[k] = v
                break
            except Exception as e:
                print(f"[WARN] Error reading {candidate}: {e}")


class GeminiFallbackClassifier:
    """Structured Gemini Free-Tier Fallback classifier with rich OCR context & strict JSON output."""
    def __init__(self, rate_limiter: TokenBucketRateLimiter, model: str = "gemini-3.6-flash"):
        self.rate_limiter = rate_limiter
        self.model = model
        self._client = None
        self.quota_exhausted = False

        key = os.environ.get("GEMINI_API_KEY", "").strip()
        if key in {"your-api-key", "your_api_key", ""}:
            self.api_key = None
        else:
            self.api_key = key

    def is_available(self) -> bool:
        return bool(self.api_key) and not self.quota_exhausted

    def _get_client(self):
        if self._client is None and self.is_available():
            from google import genai
            self._client = genai.Client(api_key=self.api_key)
        return self._client

    def classify_with_context(
        self,
        image_path: Path,
        pil_image: Image.Image,
        ocr_data: Dict[str, Any],
        signals: Dict[str, Any],
        clip_data: Dict[str, Any],
    ) -> Tuple[str, float, str, List[str]]:
        """
        Sends structured multimodal prompt with derived OCR & layout context to Gemini.
        Returns: (category, confidence, reason, evidence)
        """
        if self.quota_exhausted:
            return "UNKNOWN", 0.0, "Gemini quota exhausted", []

        client = self._get_client()
        if not client:
            return "UNKNOWN", 0.0, "API client unavailable", []

        self.rate_limiter.wait()

        max_retries = 2
        backoff = 4.0

        ocr_text_preview = ocr_data.get("ocr_text", "")[:250].replace("\n", " ")
        prompt = (
            "You are an expert image dataset classification assistant.\n"
            "Analyze this image to categorize it into exactly one of:\n"
            "- 'PHOTO': Genuine camera photographs, portraits, landscapes, family photos, document scans.\n"
            "- 'MEME': Internet memes, captioned jokes, social media screenshots, chat threads.\n"
            "- 'GREETING': Good morning/night wishes, holiday celebration cards, religious graphics, motivational quotes.\n\n"
            "Precomputed Context from Local Machine:\n"
            f"- Extracted OCR Text: \"{ocr_text_preview}\"\n"
            f"- Greeting Keyword Hits: {signals.get('greeting_keyword_hits', [])}\n"
            f"- Meme Keyword Hits: {signals.get('meme_keyword_hits', [])}\n"
            f"- Text Bounding Boxes: {ocr_data.get('text_box_count', 0)}, Text Coverage: {ocr_data.get('text_area_ratio', 0.0):.1%}\n"
            f"- Local CLIP Top Prediction: {clip_data.get('top_label', 'UNKNOWN')} (margin: {clip_data.get('margin', 0.0):.2f})\n\n"
            "Respond ONLY with a JSON object conforming to this schema:\n"
            "{\n"
            "  \"category\": \"PHOTO\" | \"MEME\" | \"GREETING\",\n"
            "  \"confidence\": <float between 0.0 and 1.0>,\n"
            "  \"reason\": \"<concise explanation>\",\n"
            "  \"evidence\": [\"<evidence item 1>\", \"<evidence item 2>\"]\n"
            "}"
        )

        for attempt in range(max_retries):
            try:
                img_copy = pil_image.convert("RGB")
                img_copy.thumbnail((512, 512))

                from google.genai import types
                response = client.models.generate_content(
                    model=self.model,
                    contents=[img_copy, prompt],
                    config=types.GenerateContentConfig(
                        response_mime_type="application/json",
                        temperature=0.1,
                    ),
                )

                text = response.text.strip()
                if text.startswith("```"):
                    text = re.sub(r"^```(?:json)?\n?", "", text)
                    text = re.sub(r"\n?```$", "", text).strip()

                data = json.loads(text)
                category = str(data.get("category", "PHOTO")).upper()
                confidence = float(data.get("confidence", 0.90))
                reason = str(data.get("reason", "Gemini structured classification"))
                evidence = data.get("evidence", [])
                if not isinstance(evidence, list):
                    evidence = [str(evidence)]

                if category not in {"PHOTO", "MEME", "GREETING"}:
                    category = "PHOTO"

                return category, confidence, reason, evidence

            except Exception as e:
                err_str = str(e)
                if "Quota exceeded" in err_str or "quotaValue" in err_str:
                    print("\n[INFO] Gemini Free Tier daily quota reached. Disabling cloud fallback for remaining items.")
                    self.quota_exhausted = True
                    return "UNKNOWN", 0.0, "Gemini daily quota limit reached", []

                if "429" in err_str or "RESOURCE_EXHAUSTED" in err_str or "503" in err_str:
                    print(f"\n[WARN] Gemini rate limit hit, backing off {backoff:.1f}s (attempt {attempt+1}/{max_retries})...")
                    time.sleep(backoff)
                    backoff *= 2
                else:
                    print(f"\n[ERROR] Gemini classification failed for {image_path.name}: {e}")
                    return "UNKNOWN", 0.0, str(e), []

        return "UNKNOWN", 0.0, "Max retries exceeded", []


def extract_year_and_memories_root(path: Path, base_memories_dir: Optional[Path] = None) -> Tuple[str, Path]:
    """Extracts the 4-digit year and memories root directory from an image path."""
    parts = list(path.parts)
    if "quarantine" in parts:
        q_idx = parts.index("quarantine")
        if q_idx + 1 < len(parts) and re.fullmatch(r"\d{4}", parts[q_idx + 1]):
            year = parts[q_idx + 1]
            root = Path(*parts[:q_idx])
            return year, root

    for i in range(len(parts) - 1, 0, -1):
        if re.fullmatch(r"\d{4}", parts[i]):
            year = parts[i]
            root = Path(*parts[:i])
            return year, root

    fallback_root = base_memories_dir or path.parent.parent
    return str(datetime.now().year), fallback_root


def get_unique_destination_path(target_dir: Path, original_name: str) -> Path:
    """Generate collision-free destination path like media-extractor."""
    target_dir.mkdir(parents=True, exist_ok=True)
    target_path = target_dir / original_name
    if not target_path.exists():
        return target_path

    stem = target_path.stem
    suffix = target_path.suffix
    counter = 1
    while True:
        candidate = target_dir / f"{stem}_{counter}{suffix}"
        if not candidate.exists():
            return candidate
        counter += 1


def create_windows_shortcut(target_path: Path, shortcut_path: Path) -> bool:
    """Creates a Windows .lnk Shell Shortcut using PowerShell/WScript.Shell."""
    if sys.platform != "win32":
        return False
    try:
        target_str = str(target_path.resolve()).replace("'", "''")
        shortcut_str = str(shortcut_path.resolve()).replace("'", "''")
        ps_cmd = (
            f"$ws = New-Object -ComObject WScript.Shell; "
            f"$s = $ws.CreateShortcut('{shortcut_str}'); "
            f"$s.TargetPath = '{target_str}'; "
            f"$s.Save()"
        )
        res = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_cmd],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        return res.returncode == 0 and shortcut_path.exists()
    except Exception:
        return False


def create_review_link(
    original_path: Path,
    memories_root: Path,
    year: str,
    link_type: str = "auto"
) -> Optional[Path]:
    """
    Creates a shortcut or link to the original file in:
    memories_root / "quarantine" / year / "review" / original_path.name

    - link_type="auto": Relative symbolic link on POSIX; on Windows tries symlink and falls back to .lnk/.url.
    - link_type="symlink": Creates a symbolic link.
    - link_type="shortcut": On Windows, creates a .lnk (or .url) shortcut; on POSIX falls back to symlink.
    """
    try:
        original_path = Path(original_path)
        review_dir = memories_root / "quarantine" / year / "review"
        review_dir.mkdir(parents=True, exist_ok=True)

        if link_type == "shortcut" and sys.platform == "win32":
            link_name = f"{original_path.name}.lnk"
        else:
            link_name = original_path.name

        link_path = review_dir / link_name

        # Check existing link/shortcut for idempotency
        if os.path.islink(link_path):
            try:
                raw_target = os.readlink(link_path)
                target_p = Path(raw_target)
                resolved_target = (link_path.parent / target_p).resolve() if not target_p.is_absolute() else target_p.resolve()
                if resolved_target == original_path.resolve():
                    return link_path
            except Exception:
                pass
            try:
                os.unlink(link_path)
            except Exception:
                pass
        elif link_path.exists():
            if link_type == "shortcut" or link_path.suffix.lower() in (".lnk", ".url"):
                return link_path
            link_path = get_unique_destination_path(review_dir, link_name)

        # Attempt symlink if auto or symlink (or non-win32)
        if link_type in ("auto", "symlink") or sys.platform != "win32":
            try:
                try:
                    rel_target = os.path.relpath(original_path, review_dir)
                except ValueError:
                    rel_target = str(original_path)
                os.symlink(rel_target, link_path)
                return link_path
            except OSError as e:
                if link_type == "symlink" or sys.platform != "win32":
                    print(f"[WARN] Could not create review symlink at {link_path}: {e}")
                    return None

        # Shortcut creation for Windows
        shortcut_path = review_dir / f"{original_path.name}.lnk"
        if create_windows_shortcut(original_path, shortcut_path):
            return shortcut_path

        # Fallback: InternetShortcut (.url) natively opened by Windows Explorer
        url_path = review_dir / f"{original_path.name}.url"
        try:
            with open(url_path, "w", encoding="utf-8") as f:
                f.write(f"[InternetShortcut]\nURL=file:///{original_path.resolve().as_posix()}\n")
            return url_path
        except Exception as err:
            print(f"[WARN] Failed to create review shortcut for {original_path}: {err}")
            return None

    except Exception as e:
        print(f"[WARN] Failed to create review link for {original_path}: {e}")
        return None


def determine_intended_destination(
    source_path: Path,
    category: str,
    base_memories_dir: Optional[Path] = None,
    quarantine: bool = False,
    rescue: bool = False,
    staged_info: Optional[Dict[str, Any]] = None,
) -> Optional[Path]:
    """Determines the intended destination path before performing routing."""
    if category == "UNKNOWN" or "review" in source_path.parts:
        return None

    if staged_info:
        orig_path = Path(staged_info["original_path"])
        orig_year = str(staged_info.get("original_year")) if staged_info.get("original_year") else None
        if not orig_year:
            orig_year, memories_root = extract_year_and_memories_root(orig_path, base_memories_dir)
        else:
            _, memories_root = extract_year_and_memories_root(orig_path, base_memories_dir)

        target_file_name = orig_path.name
        if category == "PHOTO":
            return memories_root / orig_year / "photos" / target_file_name
        elif category == "MEME":
            dest_dir = memories_root / "quarantine" / orig_year / "memes" if quarantine else memories_root / orig_year / "memes"
            return dest_dir / target_file_name
        elif category == "GREETING":
            dest_dir = memories_root / "quarantine" / orig_year / "greetings" if quarantine else memories_root / orig_year / "greetings"
            return dest_dir / target_file_name
        return None

    is_in_quarantine = "quarantine" in source_path.parts
    if (not rescue) and (not is_in_quarantine) and category == "PHOTO":
        return None

    year, memories_root = extract_year_and_memories_root(source_path, base_memories_dir)
    if rescue or is_in_quarantine:
        if category == "PHOTO":
            return memories_root / year / "photos" / source_path.name
        elif category == "MEME":
            return memories_root / "quarantine" / year / "memes" / source_path.name
        elif category == "GREETING":
            return memories_root / "quarantine" / year / "greetings" / source_path.name
        return None
    else:
        target_subfolder = "memes" if category == "MEME" else "greetings"
        if quarantine:
            return memories_root / "quarantine" / year / target_subfolder / source_path.name
        else:
            return memories_root / year / target_subfolder / source_path.name


def route_file(
    source_path: Path,
    category: str,
    action: str,
    base_memories_dir: Optional[Path] = None,
    quarantine: bool = False,
    rescue: bool = False,
    staged_info: Optional[Dict[str, Any]] = None,
) -> Optional[Path]:
    """
    Routes file into memories folder structure:
    - If staged_info is provided (file is in a staging folder):
        PHOTO    -> Returned to ~/memories/{original_year}/photos/{original_filename}
        MEME     -> ~/memories/quarantine/{original_year}/memes/ (if quarantine) or ~/memories/{original_year}/memes/
        GREETING -> ~/memories/quarantine/{original_year}/greetings/ (if quarantine) or ~/memories/{original_year}/greetings/
    - If rescue is True (or source is inside quarantine):
        PHOTO    -> ~/memories/{YYYY}/photos/
        MEME     -> ~/memories/quarantine/{YYYY}/memes/ (stays if already there)
        GREETING -> ~/memories/quarantine/{YYYY}/greetings/ (stays if already there)
    - If rescue is False:
        PHOTO    -> Remains in place in photos/ (returns None)
        MEME     -> ~/memories/quarantine/{YYYY}/memes/ (if quarantine=True) or ~/memories/{YYYY}/memes/
        GREETING -> ~/memories/quarantine/{YYYY}/greetings/ (if quarantine=True) or ~/memories/{YYYY}/greetings/
    """
    if category == "UNKNOWN" or "review" in source_path.parts:
        return None

    if staged_info:
        orig_path = Path(staged_info["original_path"])
        orig_year = str(staged_info.get("original_year")) if staged_info.get("original_year") else None
        if not orig_year:
            orig_year, memories_root = extract_year_and_memories_root(orig_path, base_memories_dir)
        else:
            _, memories_root = extract_year_and_memories_root(orig_path, base_memories_dir)

        target_file_name = orig_path.name
        if category == "PHOTO":
            dest_dir = memories_root / orig_year / "photos"
        elif category == "MEME":
            dest_dir = memories_root / "quarantine" / orig_year / "memes" if quarantine else memories_root / orig_year / "memes"
        elif category == "GREETING":
            dest_dir = memories_root / "quarantine" / orig_year / "greetings" if quarantine else memories_root / orig_year / "greetings"
        else:
            return None

        if action == "dry-run":
            return dest_dir / target_file_name

        if source_path.resolve() == (dest_dir / target_file_name).resolve():
            return dest_dir / target_file_name

        dest_path = get_unique_destination_path(dest_dir, target_file_name)
        stat = source_path.stat()
        if action == "move":
            shutil.move(str(source_path), str(dest_path))
        elif action == "copy":
            shutil.copy2(str(source_path), str(dest_path))

        try:
            os.utime(dest_path, (stat.st_atime, stat.st_mtime))
        except Exception:
            pass
        return dest_path

    if action == "dry-run":
        return None

    is_in_quarantine = "quarantine" in source_path.parts

    if (not rescue) and (not is_in_quarantine) and category == "PHOTO":
        return None

    year, memories_root = extract_year_and_memories_root(source_path, base_memories_dir)

    if rescue or is_in_quarantine:
        if category == "PHOTO":
            dest_dir = memories_root / year / "photos"
        elif category == "MEME":
            dest_dir = memories_root / "quarantine" / year / "memes"
        elif category == "GREETING":
            dest_dir = memories_root / "quarantine" / year / "greetings"
        else:
            return None
    else:
        target_subfolder = "memes" if category == "MEME" else "greetings"
        if quarantine:
            dest_dir = memories_root / "quarantine" / year / target_subfolder
        else:
            dest_dir = memories_root / year / target_subfolder

    if source_path.parent.resolve() == dest_dir.resolve():
        return None

    dest_path = get_unique_destination_path(dest_dir, source_path.name)

    stat = source_path.stat()
    if action == "move":
        shutil.move(str(source_path), str(dest_path))
    elif action == "copy":
        shutil.copy2(str(source_path), str(dest_path))

    try:
        os.utime(dest_path, (stat.st_atime, stat.st_mtime))
    except Exception:
        pass

    return dest_path


def load_processed_files(csv_path: Path) -> Set[str]:
    """Read existing CSV file to support resume."""
    processed = set()
    if csv_path.exists():
        try:
            with open(csv_path, mode="r", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    path = row.get("file_path")
                    if path:
                        processed.add(path)
        except Exception as e:
            print(f"[WARN] Could not read existing CSV for resume: {e}")
    return processed


class EvaluationEngine:
    """Evaluates classifier against labeled ground-truth CSV with precision, recall, F1, and confusion matrix."""

    @staticmethod
    def evaluate(
        labeled_csv: Path,
        pipeline_components: Tuple[ExifCameraFilter, EasyOcrEngine, SignalExtractor, LocalClipEnsembleClassifier, Optional[GeminiFallbackClassifier]],
        ocr_enabled: bool,
        clip_enabled: bool,
        gemini_enabled: bool,
        review_threshold: float,
        export_uncertain_path: Optional[Path] = None,
    ):
        exif_filter, ocr_engine, signal_extractor, clip_classifier, gemini_classifier = pipeline_components

        if not labeled_csv.exists():
            print(f"[ERROR] Labeled evaluation file not found: {labeled_csv}")
            sys.exit(1)

        rows = []
        with open(labeled_csv, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for r in reader:
                p = r.get("file_path") or r.get("path")
                cat = r.get("expected_category") or r.get("category") or r.get("label")
                if p and cat:
                    rows.append((Path(p).expanduser().resolve(), cat.strip().upper()))

        print(f"\n=== Running Evaluation on {len(rows)} Labeled Samples ===")
        print(f"Labeled Dataset: {labeled_csv}")

        classes = ["PHOTO", "MEME", "GREETING"]
        matrix = {actual: {pred: 0 for pred in classes} for actual in classes}
        uncertain_samples = []
        total_samples = len(rows)

        for img_path, actual_cat in tqdm(rows, desc="Evaluating", unit="img"):
            if not img_path.exists():
                print(f"[WARN] Skipping missing file: {img_path}")
                continue

            try:
                pil_rgb = ImagePreprocessor.load_rgb_image(img_path)
            except Exception as e:
                print(f"[WARN] Could not open {img_path}: {e}")
                continue

            # 1. Fast EXIF
            has_camera_exif, camera_info = exif_filter.is_camera_photo(img_path, pil_rgb)

            # 2. Fast CLIP Visual Triage
            if clip_enabled:
                clip_results = clip_classifier.classify_batch([pil_rgb])
                clip_data = clip_results[0]
            else:
                clip_data = {"top_label": "PHOTO", "confidence": 0.5, "margin": 0.0, "entropy": 1.0}

            # 3. Cascaded Selective OCR: Unambiguous camera photos skip heavy OCR sweeps
            skip_ocr = (
                has_camera_exif and
                clip_data.get("top_label") == "PHOTO" and
                clip_data.get("confidence", 0.0) >= 0.85
            )

            if ocr_enabled and not skip_ocr:
                ocr_data = ocr_engine.extract_features(img_path, pil_rgb, has_camera_exif=has_camera_exif)
            else:
                ocr_data = {"ocr_text": "", "ocr_confidence": 0.0, "text_box_count": 0, "text_area_ratio": 0.0}

            # 4. Signals
            signals = signal_extractor.extract_signals(pil_rgb, ocr_data, has_camera_exif)

            # 5. Decision Engine
            decision = HierarchicalDecisionEngine.decide(
                has_camera_exif, camera_info, ocr_data, signals, clip_data, review_threshold=review_threshold
            )

            pred_cat = decision["category"]
            uncertainty = decision["uncertainty"]

            if decision["needs_review"]:
                uncertain_samples.append((img_path, actual_cat, pred_cat, uncertainty, decision["decision_reason"]))

            if actual_cat in matrix and pred_cat in matrix[actual_cat]:
                matrix[actual_cat][pred_cat] += 1

        # Compute Metrics
        correct = sum(matrix[c][c] for c in classes)
        total_eval = sum(sum(matrix[c].values()) for c in classes) or 1
        overall_accuracy = (correct / total_eval) * 100.0

        print("\n=== Evaluation Results ===")
        print(f"Overall Accuracy: {overall_accuracy:.2f}% ({correct}/{total_eval})")
        print(f"Abstention / Review Rate: {len(uncertain_samples)/total_eval * 100:.2f}% ({len(uncertain_samples)} flagged)")

        print("\n--- Confusion Matrix (Rows: Actual, Columns: Predicted) ---")
        header = f"{'Actual \\ Pred':<15} | {'PHOTO':<8} | {'MEME':<8} | {'GREETING':<8}"
        print(header)
        print("-" * len(header))
        for actual in classes:
            row_str = f"{actual:<15} | {matrix[actual]['PHOTO']:<8} | {matrix[actual]['MEME']:<8} | {matrix[actual]['GREETING']:<8}"
            print(row_str)

        print("\n--- Per-Class Metrics ---")
        metrics_header = f"{'Class':<12} | {'Precision':<10} | {'Recall':<10} | {'F1-Score':<10} | {'Support':<8}"
        print(metrics_header)
        print("-" * len(metrics_header))

        for c in classes:
            tp = matrix[c][c]
            fp = sum(matrix[other][c] for other in classes if other != c)
            fn = sum(matrix[c][other] for other in classes if other != c)
            support = sum(matrix[c].values())

            precision = (tp / (tp + fp)) if (tp + fp) > 0 else 0.0
            recall = (tp / (tp + fn)) if (tp + fn) > 0 else 0.0
            f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0

            print(f"{c:<12} | {precision * 100:<9.2f}% | {recall * 100:<9.2f}% | {f1 * 100:<9.2f}% | {support:<8}")

        if export_uncertain_path and uncertain_samples:
            with open(export_uncertain_path, "w", newline="", encoding="utf-8") as f:
                writer = csv.writer(f)
                writer.writerow(["file_path", "actual_category", "predicted_category", "uncertainty", "reason"])
                for s in uncertain_samples:
                    writer.writerow([str(s[0]), s[1], s[2], f"{s[3]:.4f}", s[4]])
            print(f"\n[INFO] Exported {len(uncertain_samples)} uncertain samples to: {export_uncertain_path}")


def main():
    load_env_file()

    parser = argparse.ArgumentParser(description="Media Extractor High-Accuracy Image Classifier (v2.0)")
    parser.add_argument("--source-dir", type=str, default=str(Path.home() / "memories"),
                        help="Root directory containing images to classify (default: ~/memories)")
    parser.add_argument("--input-manifest", type=str, default=None,
                        help="UTF-8 file containing one image path per line; limits processing to this run's outputs")
    parser.add_argument("--action", choices=["dry-run", "move", "copy"], default="dry-run",
                        help="Action to perform on classified memes/greetings (default: dry-run)")
    parser.add_argument("--quarantine", action="store_true",
                        help="Route memes/greetings to ~/memories/quarantine/{YYYY}/memes|greetings/")
    parser.add_argument("--rescue", action="store_true",
                        help="Rescue mode: rescans quarantine directory and returns valid photos back to ~/memories/{YYYY}/photos/")
    parser.add_argument("--threshold", type=float, default=0.65,
                        help="Confidence threshold for decision engine (default: 0.65)")
    parser.add_argument("--ambiguity-margin", type=float, default=0.10,
                        help="Margin threshold between top 2 classes to trigger Gemini fallback (default: 0.10)")
    parser.add_argument("--rate-limit-rpm", type=float, default=12.0,
                        help="Max Gemini API requests per minute for Free Tier (default: 12.0)")
    parser.add_argument("--output-csv", type=str, default="classification_results.csv",
                        help="Path to output CSV log (default: classification_results.csv)")
    parser.add_argument("--review-threshold", type=float, default=0.40,
                        help="Uncertainty threshold above which items are routed to review queue (default: 0.40)")
    parser.add_argument("--review-csv", type=str, default="review_queue.csv",
                        help="Path to review queue CSV for uncertain cases (default: review_queue.csv)")
    parser.add_argument("--create-review-links", dest="create_review_links", action="store_true", default=True,
                        help="Create shortcuts/symlinks for review items in ~/memories/quarantine/{YYYY}/review (default: True)")
    parser.add_argument("--no-review-links", dest="create_review_links", action="store_false",
                        help="Disable creating shortcuts/symlinks for review items")
    parser.add_argument("--review-link-type", choices=["auto", "symlink", "shortcut"], default="auto",
                        help="Review link type: auto (symlink on Unix, fallback on Windows), symlink, or shortcut (default: auto)")
    parser.add_argument("--device", type=str, default="cpu",
                        help="Device for local CLIP inference: cpu or cuda (default: cpu)")
    parser.add_argument("--model", type=str, default="gemini-3.6-flash",
                        help="Gemini model to use for fallback (default: gemini-3.6-flash)")
    parser.add_argument("--batch-size", type=int, default=8,
                        help="Batch size for local CLIP inference on CPU (default: 8)")
    parser.add_argument("--limit", type=int, default=0,
                        help="Limit number of images to process (0 = unlimited)")
    parser.add_argument("--ocr-languages", type=str, default="en",
                        help="Comma-separated language codes for EasyOCR (default: 'en')")
    parser.add_argument("--no-ocr", action="store_true",
                        help="Disable local OCR engine")
    parser.add_argument("--no-clip", action="store_true",
                        help="Disable local CLIP vision model")
    parser.add_argument("--no-gemini", action="store_true",
                        help="Disable Gemini fallback even if GEMINI_API_KEY is present")
    parser.add_argument("--evaluate", type=str, default=None,
                        help="Path to labeled CSV (file_path, expected_category) to evaluate model performance")
    parser.add_argument("--export-uncertain", type=str, default=None,
                        help="Path to export uncertain samples during evaluation for manual review")
    parser.add_argument("--state-db", type=str, default=None,
                        help="Path to SQLite progress-state database (default: <source-dir>/.classifier-state/classification.sqlite3)")
    parser.add_argument("--no-resume", action="store_true",
                        help="Disable progress-state resume (default is resume enabled)")
    parser.add_argument("--fresh-run", action="store_true",
                        help="Start fresh run ID without resuming in-flight items, preserving historical items")
    parser.add_argument("--lease-timeout", type=float, default=300.0,
                        help="Heartbeat lease timeout in seconds (default: 300.0)")
    parser.add_argument("--no-reconcile-stale", action="store_true",
                        help="Disable startup reconciliation of abandoned runs")
    parser.add_argument("--fingerprint-strategy", choices=["cheap", "full-sha256"], default="cheap",
                        help="Fingerprint strategy: cheap (stat + head/tail hash) or full-sha256 (default: cheap)")
    parser.add_argument("--sync-csv", action="store_true",
                        help="Export/resync full CSV reports from SQLite state store")

    args = parser.parse_args()

    # Parse OCR languages
    ocr_langs = [lang.strip() for lang in args.ocr_languages.split(",") if lang.strip()]
    if not ocr_langs:
        ocr_langs = ["en"]

    # Initialize components
    rate_limiter = TokenBucketRateLimiter(rpm=args.rate_limit_rpm)
    exif_filter = ExifCameraFilter()
    ocr_engine = EasyOcrEngine(languages=ocr_langs, gpu=(args.device == "cuda"))
    signal_extractor = SignalExtractor()
    clip_classifier = LocalClipEnsembleClassifier(device=args.device)
    gemini_classifier = GeminiFallbackClassifier(rate_limiter=rate_limiter, model=args.model)

    ocr_enabled = not args.no_ocr
    clip_enabled = not args.no_clip
    gemini_enabled = (not args.no_gemini) and gemini_classifier.is_available()

    # If in evaluation mode, run evaluation pipeline and exit
    if args.evaluate:
        EvaluationEngine.evaluate(
            Path(args.evaluate).expanduser().resolve(),
            (exif_filter, ocr_engine, signal_extractor, clip_classifier, gemini_classifier),
            ocr_enabled=ocr_enabled,
            clip_enabled=clip_enabled,
            gemini_enabled=gemini_enabled,
            review_threshold=args.review_threshold,
            export_uncertain_path=Path(args.export_uncertain).resolve() if args.export_uncertain else None,
        )
        return

    source_root = Path(args.source_dir).expanduser().resolve()
    csv_file = Path(args.output_csv).resolve()
    review_file = Path(args.review_csv).resolve()

    if not source_root.exists():
        print(f"[ERROR] Source directory does not exist: {source_root}")
        sys.exit(1)

    # Initialize SQLite state store
    if args.state_db:
        state_db_path = Path(args.state_db).expanduser().resolve()
    else:
        state_db_path = (source_root / ".classifier-state" / "classification.sqlite3").resolve()

    cfg_raw = (
        f"{MODEL_VERSION}:{args.action}:{args.quarantine}:{args.rescue}:"
        f"{args.threshold}:{args.ambiguity_margin}:{args.review_threshold}:"
        f"{args.ocr_languages}:{args.device}:{args.model}:"
        f"{args.no_ocr}:{args.no_clip}:{args.no_gemini}"
    )
    config_fingerprint = hashlib.sha256(cfg_raw.encode("utf-8")).hexdigest()[:16]
    run_id = f"{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"

    state_store = ClassifierStateStore(
        db_path=state_db_path,
        run_id=run_id,
        lease_timeout_sec=args.lease_timeout,
        config_fingerprint=config_fingerprint,
        model_version=MODEL_VERSION,
        action=args.action,
        quarantine=args.quarantine,
        rescue=args.rescue,
        command_line=" ".join(sys.argv),
    )

    try:
        state_store.acquire_lock()
    except ClassifierLockError as lock_err:
        print(f"[ERROR] {lock_err}")
        sys.exit(1)

    shutdown_requested = False

    def handle_signal(signum, frame):
        nonlocal shutdown_requested
        sig_name = "SIGINT" if signum == signal.SIGINT else "SIGTERM"
        print(f"\n[WARN] Caught {sig_name}; shutting down gracefully after current item/batch...")
        shutdown_requested = True

    try:
        signal.signal(signal.SIGINT, handle_signal)
        signal.signal(signal.SIGTERM, handle_signal)
    except Exception:
        pass

    if not args.no_reconcile_stale:
        recovered = state_store.recover_stale_runs()
        if recovered > 0:
            print(f"[INFO] Recovered {recovered} in-flight items from stale or interrupted runs.")

    # Import legacy CSV if available and state DB is fresh
    if csv_file.exists():
        imported = state_store.import_legacy_csv(csv_file)
        if imported > 0:
            print(f"[INFO] Imported {imported} historical records from legacy CSV: {csv_file}")

    print(f"=== Media Extractor Multi-Modal Classifier ({MODEL_VERSION}) ===")
    print(f"Source Directory:     {source_root}")
    print(f"State Database:       {state_db_path}")
    print(f"Resume:               {'DISABLED' if args.no_resume else 'ENABLED'}")
    print(f"Action:               {args.action}")
    print(f"Quarantine Mode:      {'ENABLED' if args.quarantine else 'DISABLED'}")
    print(f"Rescue Mode:          {'ENABLED (quarantine -> photos)' if args.rescue else 'DISABLED'}")
    print(f"Review Mode:          {'ENABLED (threshold=' + str(args.review_threshold) + ')'}")
    print(f"OCR Engine:           {'ENABLED (' + ','.join(ocr_langs) + ')' if ocr_enabled else 'DISABLED'}")
    print(f"CLIP Vision Model:    {'ENABLED (' + args.device + ')' if clip_enabled else 'DISABLED'}")
    print(f"Gemini Fallback:      {'ENABLED (' + args.model + ')' if gemini_enabled else 'DISABLED'}")
    print(f"Output CSV:           {csv_file}")
    print(f"Review CSV:           {review_file}")

    # Discover images either from the current-run manifest or the standalone tree scan.
    resume_enabled = (not args.no_resume) and (not args.fresh_run)
    image_paths: List[Path] = []
    item_records: Dict[str, Any] = {}
    staged_metadata: Dict[str, Dict[str, Any]] = {}
    skipped_count = 0

    if args.input_manifest:
        manifest_path = Path(args.input_manifest).expanduser().resolve()
        if not manifest_path.exists():
            print(f"[ERROR] Input manifest does not exist: {manifest_path}")
            state_store.release_lock(final_status="FAILED", error_message=f"Missing manifest: {manifest_path}")
            sys.exit(1)
        print(f"[INFO] Reading image manifest: {manifest_path}")
        with open(manifest_path, "r", encoding="utf-8") as manifest_file:
            for line in manifest_file:
                line_str = line.strip()
                if not line_str:
                    continue
                if line_str.startswith("{") and line_str.endswith("}"):
                    try:
                        data = json.loads(line_str)
                        staged = Path(data.get("staged_path", "")).expanduser().resolve()
                        orig = Path(data.get("original_path", "")).expanduser().resolve()
                        orig_year = data.get("original_year")
                        if staged.is_file() and staged.suffix.lower() in SUPPORTED_EXTENSIONS:
                            fp, sz, mt = compute_file_fingerprint(staged, strategy=args.fingerprint_strategy)
                            # Anchor canonical identity to original_path when available
                            primary_path = orig if str(orig) else staged
                            if resume_enabled and (
                                state_store.is_item_skippable(str(primary_path), fp, config_fingerprint) or
                                state_store.is_item_skippable(str(staged), fp, config_fingerprint)
                            ):
                                skipped_count += 1
                                continue

                            item_rec = state_store.register_discovered_item(
                                str(primary_path), fp, sz, mt,
                                original_path=str(orig),
                                staged_path=str(staged)
                            )
                            image_paths.append(staged)
                            item_records[str(staged)] = item_rec
                            staged_metadata[str(staged)] = {
                                "original_path": orig,
                                "original_year": orig_year,
                                "triage_category": data.get("triage_category"),
                                "triage_reason": data.get("triage_reason"),
                            }
                        continue
                    except json.JSONDecodeError:
                        pass
                path = Path(line.rstrip("\r\n")).expanduser().resolve()
                if path.is_file() and path.suffix.lower() in SUPPORTED_EXTENSIONS:
                    fp, sz, mt = compute_file_fingerprint(path, strategy=args.fingerprint_strategy)
                    if resume_enabled and state_store.is_item_skippable(str(path), fp, config_fingerprint):
                        skipped_count += 1
                        continue
                    item_rec = state_store.register_discovered_item(str(path), fp, sz, mt)
                    image_paths.append(path)
                    item_records[str(path)] = item_rec
    else:
        print("[INFO] Scanning for image files using candidate discovery...")
        summary = state_store.discover_candidates(
            source_dir=source_root,
            resume_enabled=resume_enabled,
            rescue=args.rescue,
            fingerprint_strategy=args.fingerprint_strategy,
            config_fingerprint=config_fingerprint
        )
        skipped_count = summary.already_completed
        total_considered = summary.already_completed + summary.final_candidates_count + summary.review_pending

        print(f"[INFO] Discovered candidates breakdown: newly_extracted={summary.newly_extracted}, filesystem={summary.filesystem_discovered}, db_resumed={summary.database_resumed}, staging_recovered={summary.staging_recovery}")
        print(f"[INFO] Classification resume progress: {summary.completed_percentage:.1f}% previously completed ({summary.already_completed}/{total_considered} photos), {summary.remaining_percentage:.1f}% remaining ({summary.final_candidates_count}/{total_considered} photos) | Estimated remaining time: ~{summary.estimated_time_formatted} (~{summary.seconds_per_image:.1f}s/img via {summary.rate_source} on {summary.cpu_cores} CPUs)")
        if skipped_count > 0:
            print(f"[INFO] Resumed: skipped {skipped_count} completed files matching current state and fingerprint.")

        for cand_str in summary.candidate_paths:
            path = Path(cand_str)
            try:
                fp, sz, mt = compute_file_fingerprint(path, strategy=args.fingerprint_strategy)
            except Exception:
                continue
            item_rec = state_store.register_discovered_item(str(path), fp, sz, mt)
            image_paths.append(path)
            item_records[str(path)] = item_rec

    if args.input_manifest:
        total_manifest = skipped_count + len(image_paths)
        if total_manifest > 0 and skipped_count > 0:
            c_pct = round((skipped_count * 100.0) / total_manifest, 1)
            r_pct = round((len(image_paths) * 100.0) / total_manifest, 1)
            cpu_cores = os.cpu_count() or 4
            measured_sec = state_store.get_measured_seconds_per_image()
            _, eta_fmt, sec_per_img, rate_src = estimate_processing_time(
                len(image_paths), cpu_cores=cpu_cores, measured_seconds_per_image=measured_sec
            )
            print(f"[INFO] Classification resume progress: {c_pct:.1f}% previously completed ({skipped_count}/{total_manifest} photos), {r_pct:.1f}% remaining ({len(image_paths)}/{total_manifest} photos) | Estimated remaining time: ~{eta_fmt} (~{sec_per_img:.1f}s/img via {rate_src} on {cpu_cores} CPUs)")
        elif skipped_count > 0:
            print(f"[INFO] Resumed: skipped {skipped_count} completed files matching current state and fingerprint.")

    if args.limit > 0:
        image_paths = image_paths[:args.limit]


    total_images = len(image_paths)
    print(f"[INFO] Found {total_images} new images to process.")
    if total_images == 0:
        print("[INFO] No pending images found. Done.")
        state_store.release_lock(final_status="COMPLETED")
        if args.sync_csv:
            state_store.export_csv(csv_file, review_file, CSV_HEADER)
        return

    # Setup CSV output
    csv_exists = csv_file.exists()
    csv_handle = open(csv_file, mode="a", newline="", encoding="utf-8")
    csv_writer = csv.writer(csv_handle)
    if not csv_exists:
        csv_writer.writerow(CSV_HEADER)
        csv_handle.flush()

    review_exists = review_file.exists()
    review_handle = open(review_file, mode="a", newline="", encoding="utf-8")
    review_writer = csv.writer(review_handle)
    if not review_exists:
        review_writer.writerow(CSV_HEADER)
        review_handle.flush()

    stats = {"EXIF_CAMERA": 0, "OCR_SIGNAL": 0, "CLIP_ENSEMBLE": 0, "HEURISTIC": 0, "GEMINI_FALLBACK": 0, "REVIEW": 0}
    category_stats = {"PHOTO": 0, "MEME": 0, "GREETING": 0, "UNKNOWN": 0}

    batch_size = max(1, args.batch_size)
    progress_bar = tqdm(total=total_images, desc="Classifying", unit="img")

    from concurrent.futures import ThreadPoolExecutor
    io_pool = ThreadPoolExecutor(max_workers=min(4, os.cpu_count() or 4))

    def load_item(p: Path) -> Dict[str, Any]:
        has_cam, cam_info = exif_filter.is_camera_photo(p)
        try:
            img = ImagePreprocessor.load_rgb_image(p)
            return {
                "path": p,
                "pil_image": img,
                "has_camera_exif": has_cam,
                "camera_info": cam_info,
                "error": None,
            }
        except Exception as e:
            return {
                "path": p,
                "pil_image": None,
                "has_camera_exif": has_cam,
                "camera_info": cam_info,
                "error": str(e),
            }

    # Prime prefetcher with first batch
    next_chunk_future = None
    if total_images > 0:
        first_chunk = image_paths[0:batch_size]
        next_chunk_future = io_pool.map(load_item, first_chunk)

    for i in range(0, total_images, batch_size):
        loaded_items = list(next_chunk_future) if next_chunk_future is not None else []

        # Claim items in state store
        chunk_item_ids = [
            item_records[str(loaded["path"])].item_id
            for loaded in loaded_items
            if str(loaded["path"]) in item_records
        ]
        state_store.transition_items(
            chunk_item_ids,
            LifecycleStatus.CLASSIFYING,
            message="Batch inference in progress",
            lease_duration_sec=args.lease_timeout,
        )

        # Trigger prefetch for the next batch asynchronously
        next_idx = i + batch_size
        if next_idx < total_images and not shutdown_requested:
            next_chunk = image_paths[next_idx:next_idx + batch_size]
            next_chunk_future = io_pool.map(load_item, next_chunk)
        else:
            next_chunk_future = None

        chunk_results = []
        valid_items = []

        for loaded in loaded_items:
            img_path = loaded["path"]
            err = loaded["error"]
            if err or loaded["pil_image"] is None:
                chunk_results.append({
                    "path": img_path,
                    "category": "UNKNOWN",
                    "confidence": 0.0,
                    "tier": "ERROR",
                    "decision_reason": f"File read error: {err}",
                    "ocr_data": {},
                    "signals": {},
                    "clip_data": None,
                    "uncertainty": 1.0,
                    "needs_review": False,
                })
                continue

            item_dict = {
                "path": img_path,
                "pil_image": loaded["pil_image"],
                "has_camera_exif": loaded["has_camera_exif"],
                "camera_info": loaded["camera_info"],
                "ocr_data": None,
                "signals": None,
                "decision": None,
                "clip_data": None,
            }
            chunk_results.append(item_dict)
            valid_items.append(item_dict)

        # 1. Fast Batched CLIP Visual Triage
        if valid_items and clip_enabled:
            clip_outputs = clip_classifier.classify_batch([it["pil_image"] for it in valid_items])
            for it, clip_res in zip(valid_items, clip_outputs):
                it["clip_data"] = clip_res
        elif valid_items and not clip_enabled:
            for it in valid_items:
                it["clip_data"] = {"top_label": "PHOTO", "confidence": 0.50, "margin": 0.0, "entropy": 1.0}

        # 2. Cascaded Selective OCR & Decision Engine
        for it in valid_items:
            img_path = it["path"]
            pil_rgb = it["pil_image"]
            has_camera_exif = it["has_camera_exif"]
            clip_data = it["clip_data"] or {}

            try:
                # Layout check
                w, h = pil_rgb.size
                aspect_ratio = float(w) / float(max(h, 1))
                is_screenshot_ratio = (
                    (0.45 <= aspect_ratio <= 0.60) or
                    (1.65 <= aspect_ratio <= 2.25)
                )

                # Cascaded bypass: Unambiguous camera photos skip heavy OCR
                skip_ocr = (
                    has_camera_exif and
                    clip_data.get("top_label") == "PHOTO" and
                    clip_data.get("confidence", 0.0) >= 0.85 and
                    not is_screenshot_ratio
                )

                if ocr_enabled and not skip_ocr:
                    ocr_data = ocr_engine.extract_features(img_path, pil_rgb, has_camera_exif=has_camera_exif)
                else:
                    ocr_data = {
                        "ocr_text": "",
                        "ocr_confidence": 0.0,
                        "text_box_count": 0,
                        "text_area_ratio": 0.0,
                        "boxes": [],
                        "top_band_count": 0,
                        "bottom_band_count": 0,
                    }
                it["ocr_data"] = ocr_data

                signals = signal_extractor.extract_signals(pil_rgb, ocr_data, has_camera_exif)
                it["signals"] = signals

                decision = HierarchicalDecisionEngine.decide(
                    has_camera_exif,
                    it["camera_info"],
                    ocr_data,
                    signals,
                    clip_data,
                    review_threshold=args.review_threshold,
                )
                it["decision"] = decision
            except Exception as e:
                print(f"[WARN] Error analyzing {img_path}: {repr(e)}")
                it["ocr_data"] = {}
                it["signals"] = {}
                it["decision"] = {
                    "category": "UNKNOWN",
                    "confidence": 0.0,
                    "tier": "ERROR",
                    "decision_reason": f"Analysis error: {repr(e)}",
                    "uncertainty": 1.0,
                    "needs_review": False,
                }

        # 3. Gemini Fallback for high uncertainty items
        for item in chunk_results:
            decision = item.get("decision")
            if not decision or decision.get("category") == "UNKNOWN":
                continue

            uncertainty = decision.get("uncertainty", 0.0)
            if uncertainty >= args.review_threshold and gemini_enabled:
                g_cat, g_conf, g_reason, g_evidence = gemini_classifier.classify_with_context(
                    item["path"],
                    item["pil_image"],
                    item["ocr_data"],
                    item["signals"],
                    item["clip_data"] or {},
                )
                if g_cat != "UNKNOWN":
                    decision["category"] = g_cat
                    decision["confidence"] = g_conf
                    decision["tier"] = "GEMINI_FALLBACK"
                    decision["decision_reason"] = f"Gemini: {g_reason}"
                    decision["uncertainty"] = max(0.05, 1.0 - g_conf)
                    decision["needs_review"] = (decision["uncertainty"] >= args.review_threshold)

        # 4. Route files, log to CSV & review queue
        for item in chunk_results:
            img_path = item["path"]
            item_rec = item_records.get(str(img_path))
            decision = item.get("decision")
            if not decision:
                category = item.get("category", "UNKNOWN")
                confidence = item.get("confidence", 0.0)
                tier = item.get("tier", "ERROR")
                decision_reason = item.get("decision_reason", "Processing failure")
                uncertainty = 1.0
                needs_review = False
            else:
                category = decision["category"]
                confidence = decision["confidence"]
                tier = decision["tier"]
                decision_reason = decision["decision_reason"]
                uncertainty = decision["uncertainty"]
                needs_review = decision["needs_review"]

            ocr_data = item.get("ocr_data", {})
            ocr_text = ocr_data.get("ocr_text", "")
            ocr_text_clean = ocr_text[:120].replace("\n", " ") if ocr_text else ""
            ocr_conf = ocr_data.get("ocr_confidence", 0.0)
            text_box_count = ocr_data.get("text_box_count", 0)
            text_area_ratio = ocr_data.get("text_area_ratio", 0.0)

            clip_data = item.get("clip_data") or {}
            clip_top = clip_data.get("top_label", "")
            clip_margin = clip_data.get("margin", 0.0)

            signals = item.get("signals", {})
            greeting_hits_str = "|".join(signals.get("greeting_keyword_hits", []))
            meme_hits_str = "|".join(signals.get("meme_keyword_hits", []))

            staged_info = staged_metadata.get(str(img_path))
            logged_file_path = str(staged_info["original_path"]) if staged_info else str(img_path)

            # Determine intended destination
            intended_dest = None
            if not needs_review:
                intended_dest = determine_intended_destination(
                    img_path, category, source_root,
                    quarantine=args.quarantine, rescue=args.rescue,
                    staged_info=staged_info,
                )

            # Phase 1: Persist result + intended destination in SQLite before moving
            if item_rec:
                state_store.persist_classification_result(
                    item_id=item_rec.item_id,
                    category=category,
                    confidence=confidence,
                    tier=tier,
                    decision_reason=decision_reason,
                    uncertainty=uncertainty,
                    intended_destination=str(intended_dest) if intended_dest else None,
                    needs_review=needs_review,
                )

            relocated_to = ""
            # In review mode, protect uncertain items: keep them in photos/
            if needs_review:
                stats["REVIEW"] += 1
                if staged_info and args.action != "dry-run":
                    # If this file was staged by Java triage, restore it back to its original location in photos/
                    orig_target = Path(staged_info["original_path"])
                    if img_path.exists() and img_path.resolve() != orig_target.resolve():
                        orig_target.parent.mkdir(parents=True, exist_ok=True)
                        dest_file = orig_target
                        if dest_file.exists():
                            if dest_file.stat().st_size != img_path.stat().st_size:
                                dest_file = get_unique_destination_path(orig_target.parent, orig_target.name)
                        shutil.move(str(img_path), str(dest_file))
                        logged_file_path = str(dest_file)

                if getattr(args, "create_review_links", True):
                    try:
                        orig_p = Path(logged_file_path)
                        item_year = str(staged_info.get("original_year")) if staged_info and staged_info.get("original_year") else None
                        if not item_year:
                            item_year, item_root = extract_year_and_memories_root(orig_p, source_root)
                        else:
                            _, item_root = extract_year_and_memories_root(orig_p, source_root)
                        created_link = create_review_link(
                            original_path=orig_p,
                            memories_root=item_root,
                            year=item_year,
                            link_type=getattr(args, "review_link_type", "auto"),
                        )
                        if created_link:
                            stats["REVIEW_LINKS"] = stats.get("REVIEW_LINKS", 0) + 1
                    except Exception as e:
                        print(f"[WARN] Failed to create review link for {logged_file_path}: {e}")

                review_writer.writerow([
                    logged_file_path, category, f"{confidence:.4f}", tier, decision_reason,
                    ocr_text_clean, f"{ocr_conf:.4f}", text_box_count, f"{text_area_ratio:.4f}",
                    clip_top, f"{clip_margin:.4f}", greeting_hits_str, meme_hits_str,
                    f"{uncertainty:.4f}", MODEL_VERSION, "", time.strftime("%Y-%m-%d %H:%M:%S")
                ])
                review_handle.flush()
                try:
                    os.fsync(review_handle.fileno())
                except Exception:
                    pass
            else:
                if item_rec:
                    state_store.persist_routing_started(item_rec.item_id)
                try:
                    new_path = route_file(
                        img_path, category, args.action, source_root,
                        quarantine=args.quarantine, rescue=args.rescue,
                        staged_info=staged_info
                    )
                    if new_path:
                        relocated_to = str(new_path)
                        if item_rec:
                            state_store.persist_routing_completed(item_rec.item_id, relocated_to)
                    else:
                        # route_file returned None: handle staged unknown files and in-place photos
                        if staged_info:
                            if category == "UNKNOWN" or tier == "ERROR":
                                # Safely return unclassifiable staged file back to original location
                                restored_path = route_file(
                                    img_path, "PHOTO", args.action, source_root,
                                    quarantine=args.quarantine, rescue=args.rescue,
                                    staged_info=staged_info
                                )
                                relocated_to = str(restored_path) if restored_path else str(staged_info["original_path"])
                                if item_rec:
                                    state_store.mark_failed(item_rec.item_id, f"Unclassifiable image restored to {relocated_to}", retryable=False)
                            else:
                                if item_rec:
                                    state_store.mark_failed(item_rec.item_id, "Could not determine routing destination for staged file", retryable=False)
                        else:
                            if category == "PHOTO" and not args.rescue:
                                relocated_to = str(img_path)
                                if item_rec:
                                    state_store.persist_routing_completed(item_rec.item_id, relocated_to)
                            elif args.action == "dry-run":
                                relocated_to = ""
                                if item_rec:
                                    state_store.persist_routing_completed(item_rec.item_id, relocated_to)
                            else:
                                if item_rec:
                                    state_store.mark_failed(item_rec.item_id, "No destination determined for file", retryable=False)
                except Exception as e:
                    if item_rec:
                        state_store.mark_failed(item_rec.item_id, str(e), retryable=True)
                    decision_reason += f" | Route error: {e}"

            stats[tier] = stats.get(tier, 0) + 1
            category_stats[category] = category_stats.get(category, 0) + 1
            timestamp = time.strftime("%Y-%m-%d %H:%M:%S")

            csv_writer.writerow([
                logged_file_path, category, f"{confidence:.4f}", tier, decision_reason,
                ocr_text_clean, f"{ocr_conf:.4f}", text_box_count, f"{text_area_ratio:.4f}",
                clip_top, f"{clip_margin:.4f}", greeting_hits_str, meme_hits_str,
                f"{uncertainty:.4f}", MODEL_VERSION, relocated_to, timestamp
            ])

        csv_handle.flush()
        try:
            os.fsync(csv_handle.fileno())
        except Exception:
            pass

        progress_bar.update(len(loaded_items))
        progress_bar.set_postfix({
            "photos": category_stats.get("PHOTO", 0),
            "memes": category_stats.get("MEME", 0),
            "greetings": category_stats.get("GREETING", 0),
            "review": stats.get("REVIEW", 0),
        })

        if shutdown_requested:
            print("\n[INFO] Graceful shutdown requested; checkpointing progress and stopping.")
            remaining_paths = image_paths[i + batch_size:]
            remaining_ids = [item_records[str(p)].item_id for p in remaining_paths if str(p) in item_records]
            state_store.transition_items(remaining_ids, LifecycleStatus.CANCELLED, "Cancelled by user/signal")
            break

    io_pool.shutdown(wait=False)
    csv_handle.close()
    review_handle.close()
    progress_bar.close()

    final_status = "CANCELLED" if shutdown_requested else "COMPLETED"
    state_store.release_lock(final_status=final_status)
    if args.sync_csv or resume_enabled:
        state_store.export_csv(csv_file, review_file, CSV_HEADER)

    print(f"\n=== Classification Summary ({MODEL_VERSION}) ===")
    print(f"Total processed: {total_images}")
    print(f"Categories:      PHOTOS: {category_stats.get('PHOTO', 0)} | MEMES: {category_stats.get('MEME', 0)} | GREETINGS: {category_stats.get('GREETING', 0)}")
    print(f"Tiers used:      EXIF: {stats.get('EXIF_CAMERA', 0)} | OCR Signal: {stats.get('OCR_SIGNAL', 0)} | CLIP: {stats.get('CLIP_ENSEMBLE', 0)} | Heuristic: {stats.get('HEURISTIC', 0)} | Gemini: {stats.get('GEMINI_FALLBACK', 0)}")
    print(f"Review Queue:    {stats.get('REVIEW', 0)} items flagged with uncertainty >= {args.review_threshold}")
    print(f"Results log:     {csv_file}")
    if stats.get("REVIEW", 0) > 0:
        print(f"Review log:      {review_file}")
        if stats.get("REVIEW_LINKS", 0) > 0:
            print(f"Review links:    {stats.get('REVIEW_LINKS', 0)} links created in ~/memories/quarantine/{{YYYY}}/review/")


if __name__ == "__main__":
    main()
