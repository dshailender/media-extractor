#!/usr/bin/env python3
"""
scripts/test_classifier.py

Automated deterministic unit & integration tests for media-extractor image classifier:
- TokenBucketRateLimiter timing
- ExifCameraFilter detection
- Collision-safe destination naming
- Routing logic (standard, quarantine, rescue, dry-run)
- SignalExtractor Unicode & multilingual keyword detection
- HierarchicalDecisionEngine decision rules:
  - Greeting text classification
  - Meme caption layout & keyword classification
  - Natural photo with zero OCR
  - Text-heavy document scan protection
  - Conflicting signals triggering review mode
- ImagePreprocessor EXIF orientation transpose and variant generation
- Gemini fallback JSON parsing & markdown stripping
- Extended CSV escaping and resume compatibility
- Synthetic Pillow image integration tests (Good Morning, Birthday, Meme, Landscape, Document)
"""

import csv
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

import numpy as np
import piexif
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from classify_memes import (
    CAMERA_MAKE_TAG,
    CAMERA_MODEL_TAG,
    CSV_HEADER,
    ExifCameraFilter,
    GeminiFallbackClassifier,
    HierarchicalDecisionEngine,
    ImagePreprocessor,
    LocalClipEnsembleClassifier,
    SignalExtractor,
    TokenBucketRateLimiter,
    extract_year_and_memories_root,
    get_unique_destination_path,
    load_processed_files,
    route_file,
    create_review_link,
)


class TestClassifier(unittest.TestCase):

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="media_extractor_test_"))

    def tearDown(self):
        if self.test_dir.exists():
            shutil.rmtree(self.test_dir)

    def test_rate_limiter_throttling(self):
        # 120 RPM = 0.5s interval
        limiter = TokenBucketRateLimiter(rpm=120.0)
        t0 = time.monotonic()
        limiter.wait()
        limiter.wait()
        elapsed = time.monotonic() - t0
        self.assertGreaterEqual(elapsed, 0.45, "Rate limiter did not throttle requests properly")

    def test_exif_camera_detection(self):
        camera_img_path = self.test_dir / "camera_photo.jpg"
        img = Image.new("RGB", (200, 200), color="blue")

        zeroth_ifd = {
            piexif.ImageIFD.Make: b"Apple",
            piexif.ImageIFD.Model: b"iPhone 15 Pro",
        }
        exif_bytes = piexif.dump({"0th": zeroth_ifd})
        img.save(camera_img_path, "JPEG", exif=exif_bytes)

        is_cam, device = ExifCameraFilter.is_camera_photo(camera_img_path)
        self.assertTrue(is_cam, "Failed to identify camera photo via EXIF")
        self.assertIn("iPhone 15 Pro", device or "")

        meme_img_path = self.test_dir / "meme_no_exif.jpg"
        img_no_exif = Image.new("RGB", (200, 200), color="yellow")
        img_no_exif.save(meme_img_path, "JPEG")

        is_cam_no, _ = ExifCameraFilter.is_camera_photo(meme_img_path)
        self.assertFalse(is_cam_no, "Non-camera image was incorrectly identified as camera photo")

    def test_collision_naming(self):
        dest_dir = self.test_dir / "memes"
        dest_dir.mkdir(parents=True)
        (dest_dir / "meme.jpg").write_text("dummy")

        unique_path = get_unique_destination_path(dest_dir, "meme.jpg")
        self.assertEqual(unique_path.name, "meme_1.jpg")

    def test_routing_logic(self):
        year_photos = self.test_dir / "memories" / "2024" / "photos"
        year_photos.mkdir(parents=True)
        sample_file = year_photos / "sample_meme.png"
        sample_file.write_text("dummy")

        res_dry = route_file(sample_file, "MEME", "dry-run", self.test_dir / "memories")
        self.assertIsNone(res_dry)
        self.assertTrue(sample_file.exists())

        res_copy = route_file(sample_file, "MEME", "copy", self.test_dir / "memories")
        self.assertIsNotNone(res_copy)
        self.assertTrue(res_copy.exists())
        self.assertEqual(res_copy.parent.name, "memes")
        self.assertTrue(sample_file.exists())

        res_move = route_file(sample_file, "MEME", "move", self.test_dir / "memories")
        self.assertIsNotNone(res_move)
        self.assertTrue(res_move.exists())
        self.assertFalse(sample_file.exists())

    def test_quarantine_routing(self):
        year_photos = self.test_dir / "memories" / "2017" / "photos"
        year_photos.mkdir(parents=True)
        meme_file = year_photos / "whatsapp_meme.jpg"
        meme_file.write_text("dummy")

        greeting_file = year_photos / "good_morning.jpg"
        greeting_file.write_text("dummy")

        q_meme = route_file(meme_file, "MEME", "move", self.test_dir / "memories", quarantine=True)
        self.assertIsNotNone(q_meme)
        self.assertEqual(q_meme, self.test_dir / "memories" / "quarantine" / "2017" / "memes" / "whatsapp_meme.jpg")
        self.assertTrue(q_meme.exists())
        self.assertFalse(meme_file.exists())

        q_greeting = route_file(greeting_file, "GREETING", "move", self.test_dir / "memories", quarantine=True)
        self.assertIsNotNone(q_greeting)
        self.assertEqual(q_greeting, self.test_dir / "memories" / "quarantine" / "2017" / "greetings" / "good_morning.jpg")
        self.assertTrue(q_greeting.exists())
        self.assertFalse(greeting_file.exists())

    def test_rescue_routing(self):
        q_memes = self.test_dir / "memories" / "quarantine" / "2017" / "memes"
        q_memes.mkdir(parents=True)
        family_photo = q_memes / "family.jpg"
        family_photo.write_text("dummy")

        friends_photo = self.test_dir / "memories" / "quarantine" / "2017" / "greetings" / "friends.jpg"
        friends_photo.parent.mkdir(parents=True)
        friends_photo.write_text("dummy")

        res_family = route_file(family_photo, "PHOTO", "move", self.test_dir / "memories", rescue=True)
        self.assertIsNotNone(res_family)
        self.assertEqual(res_family, self.test_dir / "memories" / "2017" / "photos" / "family.jpg")
        self.assertTrue(res_family.exists())
        self.assertFalse(family_photo.exists())

        res_friends = route_file(friends_photo, "PHOTO", "move", self.test_dir / "memories", rescue=True)
        self.assertIsNotNone(res_friends)
        self.assertEqual(res_friends, self.test_dir / "memories" / "2017" / "photos" / "friends.jpg")
        self.assertTrue(res_friends.exists())
        self.assertFalse(friends_photo.exists())

        actual_meme = q_memes / "actual_meme.jpg"
        actual_meme.write_text("dummy")
        res_stay = route_file(actual_meme, "MEME", "move", self.test_dir / "memories", rescue=True)
        self.assertIsNone(res_stay)
        self.assertTrue(actual_meme.exists())

    def test_signal_extractor_keyword_detection(self):
        extractor = SignalExtractor()
        dummy_img = Image.new("RGB", (800, 600), color="white")

        # 1. Greeting text
        ocr_greeting = {"ocr_text": "Wishing you a very Happy Birthday! Have a blessed day and Suprabhat.", "top_band_count": 0, "bottom_band_count": 0}
        sig_g = extractor.extract_signals(dummy_img, ocr_greeting, False)
        self.assertTrue(len(sig_g["greeting_keyword_hits"]) >= 2)
        self.assertFalse(bool(sig_g["meme_keyword_hits"]))

        # 2. Meme text
        ocr_meme = {"ocr_text": "POV: When you write code without tests and it works on production. Relatable bruh!", "top_band_count": 1, "bottom_band_count": 1}
        sig_m = extractor.extract_signals(dummy_img, ocr_meme, False)
        self.assertTrue(len(sig_m["meme_keyword_hits"]) >= 2)
        self.assertTrue(sig_m["has_caption_layout"])

        # 3. Document text
        ocr_doc = {"ocr_text": "Tax Invoice Total Amount: $150.00 Date: 2026-09-05 Account No: 12345", "top_band_count": 0, "bottom_band_count": 0}
        sig_d = extractor.extract_signals(dummy_img, ocr_doc, False)
        self.assertTrue(len(sig_d["document_keyword_hits"]) >= 2)

    def test_hierarchical_decision_rules(self):
        # 1. Strong Greeting OCR -> GREETING
        res_g = HierarchicalDecisionEngine.decide(
            has_camera_exif=False,
            camera_info=None,
            ocr_data={"ocr_text": "Good Morning have a wonderful day", "ocr_confidence": 0.85, "text_box_count": 2, "text_area_ratio": 0.15},
            signals={"greeting_keyword_hits": [r"\bgood\s*morning\b"], "meme_keyword_hits": [], "document_keyword_hits": []},
            clip_data={"top_label": "GREETING", "confidence": 0.80, "margin": 0.60, "entropy": 0.20},
        )
        self.assertEqual(res_g["category"], "GREETING")
        self.assertEqual(res_g["tier"], "OCR_SIGNAL")
        self.assertFalse(res_g["needs_review"])

        # 2. Strong Meme OCR & Caption layout -> MEME
        res_m = HierarchicalDecisionEngine.decide(
            has_camera_exif=False,
            camera_info=None,
            ocr_data={"ocr_text": "When you try your best", "ocr_confidence": 0.80, "text_box_count": 2, "text_area_ratio": 0.10},
            signals={"greeting_keyword_hits": [], "meme_keyword_hits": [r"\bwhen\s*you\b"], "document_keyword_hits": [], "is_likely_screenshot": True},
            clip_data={"top_label": "MEME", "confidence": 0.82, "margin": 0.60, "entropy": 0.25},
        )
        self.assertEqual(res_m["category"], "MEME")
        self.assertFalse(res_m["needs_review"])

        # 3. Document scan protection -> PHOTO
        res_doc = HierarchicalDecisionEngine.decide(
            has_camera_exif=False,
            camera_info=None,
            ocr_data={"ocr_text": "Invoice 10294 Total Amount Due $500.00 Signature authorized", "ocr_confidence": 0.90, "text_box_count": 6, "text_area_ratio": 0.30},
            signals={"greeting_keyword_hits": [], "meme_keyword_hits": [], "document_keyword_hits": [r"\binvoice\b"]},
            clip_data={"top_label": "PHOTO", "confidence": 0.85, "margin": 0.70, "entropy": 0.15},
        )
        self.assertEqual(res_doc["category"], "PHOTO")
        self.assertEqual(res_doc["tier"], "OCR_SIGNAL")
        self.assertIn("Document", res_doc["decision_reason"])

        # 4. Natural photo (no text) -> PHOTO
        res_p = HierarchicalDecisionEngine.decide(
            has_camera_exif=True,
            camera_info="Apple iPhone 14",
            ocr_data={"ocr_text": "", "ocr_confidence": 0.0, "text_box_count": 0, "text_area_ratio": 0.0},
            signals={"greeting_keyword_hits": [], "meme_keyword_hits": [], "document_keyword_hits": []},
            clip_data={"top_label": "PHOTO", "confidence": 0.92, "margin": 0.80, "entropy": 0.10},
        )
        self.assertEqual(res_p["category"], "PHOTO")
        self.assertEqual(res_p["tier"], "EXIF_CAMERA")
        self.assertFalse(res_p["needs_review"])

        # 5. Conflicting signals -> needs_review == True
        res_conflict = HierarchicalDecisionEngine.decide(
            has_camera_exif=False,
            camera_info=None,
            ocr_data={"ocr_text": "Hello world random sign", "ocr_confidence": 0.40, "text_box_count": 1, "text_area_ratio": 0.05},
            signals={"greeting_keyword_hits": [], "meme_keyword_hits": [], "document_keyword_hits": []},
            clip_data={"top_label": "MEME", "confidence": 0.45, "margin": 0.05, "entropy": 0.92},
            review_threshold=0.40,
        )
        self.assertTrue(res_conflict["needs_review"])
        self.assertGreaterEqual(res_conflict["uncertainty"], 0.40)

    def test_image_preprocessor_orientation_and_variants(self):
        # Create image with EXIF Orientation = 6 (Rotate 90 CW)
        img = Image.new("RGB", (200, 100), color="red")
        zeroth_ifd = {piexif.ImageIFD.Orientation: 6}
        exif_bytes = piexif.dump({"0th": zeroth_ifd})

        tmp_path = self.test_dir / "orient_test.jpg"
        img.save(tmp_path, "JPEG", exif=exif_bytes)

        with Image.open(tmp_path) as loaded_img:
            transposed = ImagePreprocessor.correct_orientation(loaded_img)
            self.assertEqual(transposed.size, (100, 200))

        # Test variant generation
        variants = ImagePreprocessor.generate_ocr_variants(transposed)
        var_names = [v[0] for v in variants]
        self.assertIn("original", var_names)
        self.assertIn("enlarged", var_names)
        self.assertIn("high_contrast_gray", var_names)
        self.assertIn("adaptive_thresh", var_names)
        self.assertIn("sharpened", var_names)

    def test_gemini_json_parsing(self):
        # 1. Standard valid JSON
        valid_json_text = '{"category": "MEME", "confidence": 0.95, "reason": "Social media post screenshot", "evidence": ["tweet banner"]}'
        data = json.loads(valid_json_text)
        self.assertEqual(data["category"], "MEME")

        # 2. Markdown wrapped JSON
        md_json = '```json\n{"category": "GREETING", "confidence": 0.92, "reason": "Morning wish card", "evidence": ["Good Morning text"]}\n```'
        clean = md_json.strip()
        if clean.startswith("```"):
            clean = clean.split("\n", 1)[1].rsplit("\n", 1)[0].strip()
        parsed = json.loads(clean)
        self.assertEqual(parsed["category"], "GREETING")

    def test_csv_escaping_and_resume(self):
        csv_file = self.test_dir / "output.csv"
        with open(csv_file, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(CSV_HEADER)
            writer.writerow([
                "/path/to/img1.jpg", "MEME", "0.9500", "OCR_SIGNAL", "Reason with, comma and \"quotes\"",
                "Caption: \"When you code, it works!\"", "0.8800", "2", "0.1200",
                "MEME", "0.4500", "", "when you", "0.1500", "2.0.0", "", "2026-09-05 18:00:00"
            ])

        processed = load_processed_files(csv_file)
        self.assertIn("/path/to/img1.jpg", processed)

    def test_manifest_limits_cli_inputs(self):
        image_path = self.test_dir / "listed.jpg"
        ignored_path = self.test_dir / "ignored.jpg"
        Image.new("RGB", (80, 80), color="blue").save(image_path)
        Image.new("RGB", (80, 80), color="red").save(ignored_path)
        manifest_path = self.test_dir / "inputs.manifest"
        manifest_path.write_text(f"{image_path}\n{self.test_dir / 'missing.jpg'}\n", encoding="utf-8")
        output_csv = self.test_dir / "results.csv"
        review_csv = self.test_dir / "review.csv"

        result = subprocess.run([
            sys.executable,
            str(Path(__file__).with_name("classify_memes.py")),
            "--source-dir", str(self.test_dir),
            "--input-manifest", str(manifest_path),
            "--output-csv", str(output_csv),
            "--review-csv", str(review_csv),
            "--no-ocr", "--no-clip", "--no-gemini",
        ], capture_output=True, text=True, check=False)

        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        with output_csv.open(encoding="utf-8", newline="") as csv_file:
            rows = list(csv.DictReader(csv_file))
        self.assertEqual([row["file_path"] for row in rows], [str(image_path)])

    def test_synthetic_pillow_integration(self):
        extractor = SignalExtractor()

        # 1. Good Morning synthetic image
        img_morning = Image.new("RGB", (600, 400), color="#FFF8DC")
        draw = ImageDraw.Draw(img_morning)
        draw.text((150, 150), "Good Morning! Have a blessed day.", fill="black")
        morning_path = self.test_dir / "morning.png"
        img_morning.save(morning_path)

        ocr_morning_mock = {"ocr_text": "Good Morning! Have a blessed day.", "ocr_confidence": 0.95, "text_box_count": 1, "text_area_ratio": 0.1}
        signals_m = extractor.extract_signals(img_morning, ocr_morning_mock, False)
        decision_m = HierarchicalDecisionEngine.decide(
            False, None, ocr_morning_mock, signals_m, {"top_label": "GREETING", "confidence": 0.85, "margin": 0.60, "entropy": 0.1}
        )
        self.assertEqual(decision_m["category"], "GREETING")

        # 2. Humorous Meme synthetic image
        img_meme = Image.new("RGB", (600, 600), color="white")
        draw = ImageDraw.Draw(img_meme)
        draw.text((50, 30), "WHEN YOU FINALLY FIX A BUG", fill="black")
        draw.text((50, 540), "AND 10 NEW ONES APPEAR", fill="black")
        meme_path = self.test_dir / "meme.png"
        img_meme.save(meme_path)

        ocr_meme_mock = {"ocr_text": "WHEN YOU FINALLY FIX A BUG AND 10 NEW ONES APPEAR", "ocr_confidence": 0.92, "text_box_count": 2, "text_area_ratio": 0.15, "top_band_count": 1, "bottom_band_count": 1}
        signals_meme = extractor.extract_signals(img_meme, ocr_meme_mock, False)
        decision_meme = HierarchicalDecisionEngine.decide(
            False, None, ocr_meme_mock, signals_meme, {"top_label": "MEME", "confidence": 0.88, "margin": 0.70, "entropy": 0.15}
        )
        self.assertEqual(decision_meme["category"], "MEME")

        # 3. Clean landscape photo
        img_landscape = Image.new("RGB", (1920, 1080), color="#2E8B57")
        landscape_path = self.test_dir / "landscape.jpg"
        img_landscape.save(landscape_path)

        ocr_empty_mock = {"ocr_text": "", "ocr_confidence": 0.0, "text_box_count": 0, "text_area_ratio": 0.0}
        signals_l = extractor.extract_signals(img_landscape, ocr_empty_mock, True)
        decision_l = HierarchicalDecisionEngine.decide(
            True, "Canon EOS 5D", ocr_empty_mock, signals_l, {"top_label": "PHOTO", "confidence": 0.94, "margin": 0.85, "entropy": 0.08}
        )
        self.assertEqual(decision_l["category"], "PHOTO")
        self.assertFalse(decision_l["needs_review"])

    def test_load_rgb_image_downscaling(self):
        # Create an oversized image (3000x2000)
        large_path = self.test_dir / "large_photo.jpg"
        img_large = Image.new("RGB", (3000, 2000), color="blue")
        img_large.save(large_path, "JPEG")

        loaded = ImagePreprocessor.load_rgb_image(large_path, max_dim=1200)
        self.assertEqual(loaded.mode, "RGB")
        self.assertLessEqual(max(loaded.size), 1200)
        self.assertEqual(loaded.size, (1200, 800))

    def test_clip_ensemble_classifier(self):
        clip = LocalClipEnsembleClassifier(device="cpu")
        sample_img = Image.new("RGB", (224, 224), color="green")
        results = clip.classify_batch([sample_img])
        self.assertEqual(len(results), 1)
        res = results[0]
        self.assertIn("top_label", res)
        self.assertIn(res["top_label"], {"PHOTO", "MEME", "GREETING"})
        self.assertIn("confidence", res)
        self.assertIn("margin", res)
        self.assertIn("scores", res)
        self.assertAlmostEqual(sum(res["scores"].values()), 1.0, places=4)


    def test_staged_file_routing(self):
        memories_root = self.test_dir / "memories"
        staging_dir = memories_root / ".staging-test-123"
        staging_dir.mkdir(parents=True)
        photos_2024 = memories_root / "2024" / "photos"
        photos_2024.mkdir(parents=True)

        orig_photo = photos_2024 / "my_pic.jpg"
        staged_photo = staging_dir / "uuid_my_pic.jpg"
        staged_photo.write_text("photo_data")

        staged_info_photo = {
            "original_path": orig_photo,
            "original_year": 2024,
            "triage_category": "NEEDS_PYTHON",
            "triage_reason": "Missing Camera EXIF",
        }

        # 1. Staged file classified as PHOTO -> returned to original photos folder
        res_photo = route_file(
            staged_photo, "PHOTO", "move", memories_root,
            quarantine=True, staged_info=staged_info_photo
        )
        self.assertIsNotNone(res_photo)
        self.assertEqual(res_photo, orig_photo)
        self.assertTrue(orig_photo.exists())
        self.assertFalse(staged_photo.exists())

        # 2. Staged file classified as MEME -> moved to quarantine memes folder
        staged_meme = staging_dir / "uuid_meme.png"
        staged_meme.write_text("meme_data")
        orig_meme = photos_2024 / "downloaded_meme.png"
        staged_info_meme = {
            "original_path": orig_meme,
            "original_year": 2024,
            "triage_category": "NEEDS_PYTHON",
            "triage_reason": "Non-camera container format: png",
        }
        res_meme = route_file(
            staged_meme, "MEME", "move", memories_root,
            quarantine=True, staged_info=staged_info_meme
        )
        expected_meme_dest = memories_root / "quarantine" / "2024" / "memes" / "downloaded_meme.png"
        self.assertIsNotNone(res_meme)
        self.assertEqual(res_meme, expected_meme_dest)
        self.assertTrue(expected_meme_dest.exists())
        self.assertFalse(staged_meme.exists())

        # 3. Dry-run with staged file -> returns destination without moving
        staged_dry = staging_dir / "uuid_dry.jpg"
        staged_dry.write_text("dry_data")
        orig_dry = photos_2024 / "dry_run_photo.jpg"
        staged_info_dry = {
            "original_path": orig_dry,
            "original_year": 2024,
        }
        res_dry = route_file(
            staged_dry, "PHOTO", "dry-run", memories_root,
            quarantine=True, staged_info=staged_info_dry
        )
        self.assertEqual(res_dry, orig_dry)
        self.assertTrue(staged_dry.exists())
        self.assertFalse(orig_dry.exists())

    def test_cli_state_db_and_resume(self):
        photos_dir = self.test_dir / "2024" / "photos"
        photos_dir.mkdir(parents=True)
        img_path = photos_dir / "camera_img.jpg"
        Image.new("RGB", (100, 100), color="green").save(img_path)

        state_db = self.test_dir / "test_state.sqlite3"
        out_csv = self.test_dir / "test_out.csv"
        rev_csv = self.test_dir / "test_rev.csv"

        # Run 1: initial processing
        cmd = [
            sys.executable,
            str(Path(__file__).with_name("classify_memes.py")),
            "--source-dir", str(self.test_dir),
            "--state-db", str(state_db),
            "--output-csv", str(out_csv),
            "--review-csv", str(rev_csv),
            "--no-ocr", "--no-clip", "--no-gemini",
        ]
        res1 = subprocess.run(cmd, capture_output=True, text=True, check=False)
        self.assertEqual(res1.returncode, 0, res1.stderr + res1.stdout)
        self.assertTrue(state_db.exists(), "State database should have been created")

        # Run 2: resume should skip unchanged completed file
        res2 = subprocess.run(cmd, capture_output=True, text=True, check=False)
        self.assertEqual(res2.returncode, 0, res2.stderr + res2.stdout)
        self.assertIn("skipped 1 completed files", res2.stdout)

    def test_staged_unclassifiable_image_not_marked_completed_at_empty_dest(self):
        """Verify that a corrupted or unclassifiable staged image is not marked COMPLETED with empty destination."""
        staging_dir = self.test_dir / ".staging-test-corrupt"
        staging_dir.mkdir(parents=True)
        corrupt_staged = staging_dir / "bad_image.jpg"
        corrupt_staged.write_bytes(b"not-a-valid-jpeg")

        photos_dir = self.test_dir / "2024" / "photos"
        photos_dir.mkdir(parents=True, exist_ok=True)
        original_target = photos_dir / "bad_image.jpg"

        manifest_path = staging_dir / "manifest.jsonl"
        manifest_path.write_text(
            json.dumps({
                "staged_path": str(corrupt_staged),
                "original_path": str(original_target),
                "original_year": 2024,
                "triage_category": "NEEDS_PYTHON",
                "triage_reason": "Corrupt test"
            }) + "\n",
            encoding="utf-8"
        )

        state_db = self.test_dir / "test_corrupt_state.sqlite3"
        out_csv = self.test_dir / "corrupt_out.csv"
        rev_csv = self.test_dir / "corrupt_rev.csv"
        cmd = [
            sys.executable,
            str(Path(__file__).with_name("classify_memes.py")),
            "--source-dir", str(self.test_dir),
            "--input-manifest", str(manifest_path),
            "--state-db", str(state_db),
            "--output-csv", str(out_csv),
            "--review-csv", str(rev_csv),
            "--no-ocr", "--no-clip", "--no-gemini",
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, check=False)
        self.assertEqual(res.returncode, 0, res.stderr + res.stdout)

        # Connect to state_db and check item status
        import sqlite3
        conn = sqlite3.connect(str(state_db))
        conn.row_factory = sqlite3.Row
        row = conn.execute("SELECT * FROM classifier_items WHERE canonical_path LIKE '%bad_image.jpg';").fetchone()
        self.assertIsNotNone(row)
        # Should NOT be COMPLETED at ""
        self.assertNotEqual(row["status"], "COMPLETED")
        self.assertEqual(row["status"], "FAILED_PERMANENT")
        conn.close()

    def test_create_review_link(self):
        memories_root = self.test_dir / "memories"
        photo_dir = memories_root / "2024" / "photos"
        photo_dir.mkdir(parents=True, exist_ok=True)
        photo_path = photo_dir / "review_sample.jpg"
        photo_path.write_bytes(b"sample_image_data")

        # 1. Test symlink creation
        link_path = create_review_link(photo_path, memories_root, "2024", link_type="symlink")
        self.assertIsNotNone(link_path)
        self.assertTrue(link_path.is_symlink() or link_path.exists())
        expected_dir = memories_root / "quarantine" / "2024" / "review"
        self.assertEqual(link_path.parent.resolve(), expected_dir.resolve())
        self.assertEqual(link_path.name, "review_sample.jpg")

        # Verify link resolves to original photo
        self.assertEqual(link_path.resolve(), photo_path.resolve())

        # 2. Test idempotency
        link_path_again = create_review_link(photo_path, memories_root, "2024", link_type="symlink")
        self.assertEqual(link_path, link_path_again)

    def test_review_mode_creates_link_and_preserves_photo(self):
        memories_root = self.test_dir / "memories"
        photo_dir = memories_root / "2024" / "photos"
        photo_dir.mkdir(parents=True, exist_ok=True)
        photo_path = photo_dir / "uncertain_meme.png"

        # Create image with text that triggers uncertainty / needs_review
        img = Image.new("RGB", (300, 300), color="white")
        draw = ImageDraw.Draw(img)
        draw.text((30, 30), "Some uncertain text caption here", fill="black")
        img.save(photo_path)

        state_db = self.test_dir / "review_state.sqlite3"
        out_csv = self.test_dir / "out.csv"
        rev_csv = self.test_dir / "rev.csv"

        # Run with low review-threshold to guarantee review mode
        cmd = [
            sys.executable,
            str(Path(__file__).with_name("classify_memes.py")),
            "--source-dir", str(memories_root),
            "--state-db", str(state_db),
            "--output-csv", str(out_csv),
            "--review-csv", str(rev_csv),
            "--review-threshold", "0.01",
            "--create-review-links",
            "--action", "move",
            "--quarantine",
            "--no-clip", "--no-gemini",
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, check=False)
        self.assertEqual(res.returncode, 0, res.stderr + res.stdout)

        # Original photo must still exist in photos/ (untouched)
        self.assertTrue(photo_path.exists())

        # Check review link was created in quarantine/2024/review
        review_link = memories_root / "quarantine" / "2024" / "review" / "uncertain_meme.png"
        self.assertTrue(review_link.exists() or review_link.is_symlink())
        self.assertEqual(review_link.resolve(), photo_path.resolve())

        # Check review_csv contains the file
        self.assertTrue(rev_csv.exists())
        with rev_csv.open(encoding="utf-8") as f:
            content = f.read()
            self.assertIn("uncertain_meme.png", content)

    def test_rescue_mode_ignores_review_dir(self):
        from classifier_state import ClassifierStateStore
        memories_root = self.test_dir / "memories"
        review_dir = memories_root / "quarantine" / "2024" / "review"
        review_dir.mkdir(parents=True, exist_ok=True)
        review_photo = review_dir / "reviewed.jpg"
        review_photo.write_bytes(b"dummy")

        store = ClassifierStateStore(self.test_dir / "dummy_state.sqlite3")
        summary = store.discover_candidates(
            source_dir=memories_root,
            resume_enabled=False,
            rescue=True,
        )
        # Should not include files in quarantine/.../review
        for cand in summary.candidate_paths:
            self.assertNotIn("review", Path(cand).parts)


if __name__ == "__main__":
    unittest.main()


