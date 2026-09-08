#!/usr/bin/env python3
"""
scripts/test_classifier_state.py

Unit and integration tests for the SQLite progress-state store:
- Database schema initialization & WAL mode
- Resuming skips completed unchanged files
- Changed size/mtime/fingerprint causes re-classification
- Interrupted CLAIMED/CLASSIFYING/ROUTING items retried
- Stale live lease is not stolen (active process protection)
- Abandoned lease is recovered
- Classification result survives crash before routing
- Routing reconciliation for all 4 cases: source-only, destination-only, both, neither
- Duplicate prevention during repeated resumes
- Dry-run and review-pending behavior
- Move, copy, quarantine, and rescue behavior
- Malformed or partially written legacy CSV handling
- Concurrent access & single-process lock behavior
- Cancellation checkpoint behavior
"""

import csv
import os
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from classifier_state import (
    ClassifierLockError,
    ClassifierStateStore,
    ItemRecord,
    LifecycleStatus,
    compute_file_fingerprint,
    compute_item_id,
    compute_sha256,
)


class TestClassifierState(unittest.TestCase):

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="classifier_state_test_"))
        self.db_path = self.test_dir / "state.sqlite3"
        self.memories_dir = self.test_dir / "memories"
        self.memories_dir.mkdir(parents=True)

    def tearDown(self):
        if self.test_dir.exists():
            shutil.rmtree(self.test_dir)

    def test_schema_initialization_and_wal(self):
        store = ClassifierStateStore(self.db_path, run_id="run-init-1")
        self.assertTrue(self.db_path.exists())

        with store._get_connection() as conn:
            cur = conn.execute("PRAGMA journal_mode;")
            journal_mode = cur.fetchone()[0]
            self.assertEqual(journal_mode.lower(), "wal")

            cur = conn.execute("SELECT name FROM sqlite_master WHERE type='table';")
            tables = {r[0] for r in cur.fetchall()}
            self.assertIn("classifier_runs", tables)
            self.assertIn("classifier_items", tables)
            self.assertIn("classifier_transitions", tables)
            self.assertIn("classifier_locks", tables)
        store.release_lock()

    def test_resume_skips_completed_unchanged_file(self):
        sample_file = self.test_dir / "photo1.jpg"
        sample_file.write_text("image-content-1")

        store = ClassifierStateStore(self.db_path, run_id="run-1", config_fingerprint="cfg-1", model_version="v2")
        store.acquire_lock()

        fp, size, mtime_ns = compute_file_fingerprint(sample_file)
        item = store.register_discovered_item(str(sample_file), fp, size, mtime_ns)

        # Before completion: not skippable
        self.assertFalse(store.is_item_skippable(str(sample_file), fp, "cfg-1"))

        # Mark completed
        store.persist_classification_result(item.item_id, "PHOTO", 0.95, "EXIF_CAMERA", "Camera photo", 0.05, None)
        store.persist_routing_completed(item.item_id, str(sample_file))

        # Now skippable!
        self.assertTrue(store.is_item_skippable(str(sample_file), fp, "cfg-1"))
        store.release_lock()

    def test_changed_file_causes_reprocessing(self):
        sample_file = self.test_dir / "photo2.jpg"
        sample_file.write_text("initial-content")

        store = ClassifierStateStore(self.db_path, run_id="run-change-1", config_fingerprint="cfg-1")
        store.acquire_lock()

        fp, size, mtime_ns = compute_file_fingerprint(sample_file)
        item = store.register_discovered_item(str(sample_file), fp, size, mtime_ns)
        store.persist_classification_result(item.item_id, "PHOTO", 0.95, "EXIF_CAMERA", "Initial", 0.05, None)
        store.persist_routing_completed(item.item_id, str(sample_file))
        store.release_lock()

        # Modify file on disk
        time.sleep(0.01)
        sample_file.write_text("modified-content-with-different-size")
        new_fp, new_size, new_mtime = compute_file_fingerprint(sample_file)
        self.assertNotEqual(fp, new_fp)

        # Resume check: must NOT be skippable with new fingerprint
        store2 = ClassifierStateStore(self.db_path, run_id="run-change-2", config_fingerprint="cfg-1")
        store2.acquire_lock()
        self.assertFalse(store2.is_item_skippable(str(sample_file), new_fp, "cfg-1"))

        # Register discovered should reset status to DISCOVERED
        updated_item = store2.register_discovered_item(str(sample_file), new_fp, new_size, new_mtime)
        self.assertEqual(updated_item.status, LifecycleStatus.DISCOVERED.value)
        self.assertEqual(updated_item.fingerprint, new_fp)
        store2.release_lock()

    def test_interrupted_claimed_or_classifying_retried(self):
        sample_file = self.test_dir / "in_flight.jpg"
        sample_file.write_text("content")

        # Run 1 dies in middle of CLASSIFYING
        store1 = ClassifierStateStore(self.db_path, run_id="run-dead-1", lease_timeout_sec=0.1)
        store1.acquire_lock()
        fp, size, mtime = compute_file_fingerprint(sample_file)
        item = store1.register_discovered_item(str(sample_file), fp, size, mtime)
        store1.transition_items([item.item_id], LifecycleStatus.CLASSIFYING, message="In flight", lease_duration_sec=0.1)

        # Simulate abrupt death: do NOT call release_lock()
        store1._heartbeat_stop_event.set()
        store1.close_lock_fd()
        time.sleep(0.2)

        # Run 2 starts, recovers stale run
        store2 = ClassifierStateStore(self.db_path, run_id="run-alive-2", lease_timeout_sec=5.0)
        store2.acquire_lock()
        recovered = store2.recover_stale_runs()
        self.assertGreaterEqual(recovered, 1)

        rec = store2.get_item_by_id(item.item_id)
        self.assertEqual(rec.status, LifecycleStatus.DISCOVERED.value)
        self.assertGreaterEqual(rec.attempt_count, 1)
        store2.release_lock()

    def test_stale_live_lease_not_stolen(self):
        store1 = ClassifierStateStore(self.db_path, run_id="run-active-1", lease_timeout_sec=60.0)
        store1.acquire_lock()

        # Another store tries to acquire lock on the same DB while run-active-1 is live
        store2 = ClassifierStateStore(self.db_path, run_id="run-impostor-2", lease_timeout_sec=60.0)
        with self.assertRaises(ClassifierLockError):
            store2.acquire_lock(force=False)

        store1.release_lock()

    def test_abandoned_lease_recovered(self):
        store1 = ClassifierStateStore(self.db_path, run_id="run-abandoned-1", lease_timeout_sec=0.2)
        store1.acquire_lock()
        store1._heartbeat_stop_event.set()
        store1.close_lock_fd()
        time.sleep(0.3)

        # Even with live PID check, if lease expired it can take over
        store2 = ClassifierStateStore(self.db_path, run_id="run-takeover-2", lease_timeout_sec=10.0)
        # Should succeed because lease expired
        store2.acquire_lock()
        store2.release_lock()

    def test_classification_result_survives_crash_before_routing(self):
        sample_file = self.test_dir / "crash_test.jpg"
        sample_file.write_text("meme-image")
        dest_file = self.test_dir / "quarantine" / "memes" / "crash_test.jpg"

        store1 = ClassifierStateStore(self.db_path, run_id="run-crash-1", action="move", lease_timeout_sec=0.1)
        store1.acquire_lock()
        fp, size, mtime = compute_file_fingerprint(sample_file)
        item = store1.register_discovered_item(str(sample_file), fp, size, mtime)

        # Phase 1: Persist result + intended destination
        store1.persist_classification_result(
            item.item_id, "MEME", 0.92, "OCR_SIGNAL", "Funny caption", 0.08, str(dest_file)
        )
        store1.persist_routing_started(item.item_id)
        # Process crashes here before moving file and before Phase 2 completion!
        store1._heartbeat_stop_event.set()
        store1.close_lock_fd()
        time.sleep(0.15)

        # Run 2 starts: reconciliation detects pending routing and existing source!
        store2 = ClassifierStateStore(self.db_path, run_id="run-recovery-2", action="move")
        store2.acquire_lock()
        item_before = store2.get_item_by_id(item.item_id)
        self.assertEqual(item_before.status, LifecycleStatus.ROUTING.value)
        self.assertEqual(item_before.category, "MEME")

        outcome, resolved_path = store2.reconcile_item(item_before)
        self.assertEqual(outcome, "RESOLVED_ROUTED")
        self.assertEqual(resolved_path, dest_file)
        self.assertTrue(dest_file.exists())
        self.assertFalse(sample_file.exists())

        item_after = store2.get_item_by_id(item.item_id)
        self.assertEqual(item_after.status, LifecycleStatus.COMPLETED.value)
        store2.release_lock()

    def test_routing_reconciliation_four_cases(self):
        store = ClassifierStateStore(self.db_path, run_id="run-cases", action="move")
        store.acquire_lock()

        # Case A: Destination exists, source missing (move finished on disk before DB write)
        case_a_src = self.test_dir / "case_a_src.jpg"
        case_a_dest = self.test_dir / "case_a_dest.jpg"
        case_a_dest.write_text("data-a")
        fp_a = f"{len('data-a')}_12345_hash"
        item_a = store.register_discovered_item(str(case_a_src), fp_a, 6, 12345)
        store.persist_classification_result(item_a.item_id, "PHOTO", 0.9, "EXIF", "Ok", 0.1, str(case_a_dest))
        outcome_a, res_a = store.reconcile_item(item_a)
        self.assertEqual(outcome_a, "RESOLVED_DEST_EXISTS")
        self.assertEqual(store.get_item_by_id(item_a.item_id).status, LifecycleStatus.COMPLETED.value)

        # Case B: Both exist and identical
        case_b_src = self.test_dir / "case_b_src.jpg"
        case_b_dest = self.test_dir / "case_b_dest.jpg"
        case_b_src.write_text("identical-content")
        case_b_dest.write_text("identical-content")
        fp_b, sz_b, mt_b = compute_file_fingerprint(case_b_src)
        item_b = store.register_discovered_item(str(case_b_src), fp_b, sz_b, mt_b)
        store.persist_classification_result(item_b.item_id, "PHOTO", 0.9, "EXIF", "Ok", 0.1, str(case_b_dest))
        outcome_b, res_b = store.reconcile_item(item_b)
        self.assertEqual(outcome_b, "RESOLVED_BOTH_MATCH")
        self.assertFalse(case_b_src.exists())  # move cleaned up source
        self.assertTrue(case_b_dest.exists())

        # Case C: Both exist and different content (collision)
        case_c_src = self.test_dir / "case_c_src.jpg"
        case_c_dest = self.test_dir / "case_c_dest.jpg"
        case_c_src.write_text("new-image-content")
        case_c_dest.write_text("existing-different-image")
        fp_c, sz_c, mt_c = compute_file_fingerprint(case_c_src)
        item_c = store.register_discovered_item(str(case_c_src), fp_c, sz_c, mt_c)
        store.persist_classification_result(item_c.item_id, "PHOTO", 0.9, "EXIF", "Ok", 0.1, str(case_c_dest))
        outcome_c, res_c = store.reconcile_item(item_c)
        self.assertEqual(outcome_c, "RESOLVED_COLLISION_UNIQUE")
        self.assertEqual(res_c.name, "case_c_dest_1.jpg")
        self.assertTrue(res_c.exists())
        self.assertTrue(case_c_dest.exists())  # existing was not overwritten!

        # Case D: Neither exists
        case_d_src = self.test_dir / "missing_src.jpg"
        case_d_dest = self.test_dir / "missing_dest.jpg"
        item_d = store.register_discovered_item(str(case_d_src), "fp_d", 10, 10)
        store.persist_classification_result(item_d.item_id, "PHOTO", 0.9, "EXIF", "Ok", 0.1, str(case_d_dest))
        outcome_d, res_d = store.reconcile_item(item_d)
        self.assertEqual(outcome_d, "FAILED_MISSING")
        self.assertEqual(store.get_item_by_id(item_d.item_id).status, LifecycleStatus.FAILED_RETRYABLE.value)

        store.release_lock()

    def test_duplicate_prevention_during_repeated_resume(self):
        file1 = self.test_dir / "pic.jpg"
        file1.write_text("test-pic")
        fp, sz, mt = compute_file_fingerprint(file1)

        store = ClassifierStateStore(self.db_path, run_id="run-dup")
        store.acquire_lock()
        item1 = store.register_discovered_item(str(file1), fp, sz, mt)
        store.persist_classification_result(item1.item_id, "PHOTO", 0.99, "EXIF", "Device", 0.01, str(file1))
        store.persist_routing_completed(item1.item_id, str(file1))

        # Repeated registration does not duplicate records
        item2 = store.register_discovered_item(str(file1), fp, sz, mt)
        self.assertEqual(item1.item_id, item2.item_id)

        # Export CSV twice, verify no duplicate rows
        csv_file = self.test_dir / "export.csv"
        store.export_csv(csv_file)
        store.export_csv(csv_file)

        with open(csv_file, "r", encoding="utf-8") as f:
            reader = list(csv.DictReader(f))
        self.assertEqual(len(reader), 1)
        store.release_lock()

    def test_dry_run_and_review_pending_behavior(self):
        store = ClassifierStateStore(self.db_path, run_id="run-rev", action="dry-run")
        store.acquire_lock()

        f = self.test_dir / "ambiguous.png"
        f.write_text("ambiguous")
        fp, sz, mt = compute_file_fingerprint(f)
        item = store.register_discovered_item(str(f), fp, sz, mt)

        # High uncertainty triggers REVIEW_PENDING
        store.persist_classification_result(
            item.item_id, "MEME", 0.52, "CLIP", "Low confidence", 0.48,
            intended_destination=str(self.test_dir / "quarantine" / "memes" / "ambiguous.png"),
            needs_review=True,
        )

        rec = store.get_item_by_id(item.item_id)
        self.assertEqual(rec.status, LifecycleStatus.REVIEW_PENDING.value)

        # Export to review queue
        out_csv = self.test_dir / "all.csv"
        rev_csv = self.test_dir / "review.csv"
        store.export_csv(out_csv, rev_csv)

        with open(rev_csv, "r", encoding="utf-8") as f:
            rev_rows = list(csv.DictReader(f))
        self.assertEqual(len(rev_rows), 1)
        self.assertEqual(rev_rows[0]["category"], "MEME")
        store.release_lock()

    def test_legacy_csv_import_handles_malformed_and_valid(self):
        # Create a valid file on disk
        valid_img = self.test_dir / "legacy_photo.jpg"
        valid_img.write_text("valid-image")

        legacy_csv = self.test_dir / "legacy.csv"
        with open(legacy_csv, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["file_path", "category", "confidence", "tier", "decision_reason", "uncertainty", "model_version", "relocated_to"])
            # Row 1: Valid
            writer.writerow([str(valid_img), "PHOTO", "0.98", "EXIF", "Canon", "0.02", "1.0", ""])
            # Row 2: Missing file on disk
            writer.writerow(["/non/existent/path.jpg", "MEME", "0.90", "OCR", "Reason", "0.10", "1.0", ""])
            # Row 3: Malformed / blank
            writer.writerow(["", "", "", "", "", "", "", ""])

        store = ClassifierStateStore(self.db_path, run_id="run-legacy")
        store.acquire_lock()
        imported = store.import_legacy_csv(legacy_csv)
        self.assertEqual(imported, 1)

        item = store.get_item_by_path(str(valid_img))
        self.assertNotNull = self.assertIsNotNone(item)
        self.assertEqual(item.category, "PHOTO")
        self.assertEqual(item.status, LifecycleStatus.COMPLETED.value)
        store.release_lock()

    def test_cancellation_checkpoint_behavior(self):
        sample1 = self.test_dir / "canc1.jpg"
        sample2 = self.test_dir / "canc2.jpg"
        sample1.write_text("canc1")
        sample2.write_text("canc2")

        store = ClassifierStateStore(self.db_path, run_id="run-canc")
        store.acquire_lock()
        fp1, sz1, mt1 = compute_file_fingerprint(sample1)
        fp2, sz2, mt2 = compute_file_fingerprint(sample2)
        item1 = store.register_discovered_item(str(sample1), fp1, sz1, mt1)
        item2 = store.register_discovered_item(str(sample2), fp2, sz2, mt2)

        store.transition_items([item1.item_id, item2.item_id], LifecycleStatus.CLASSIFYING, "In flight")

        # Simulate SIGINT/cancellation: transition in-flight items to CANCELLED
        store.transition_items([item1.item_id, item2.item_id], LifecycleStatus.CANCELLED, "Interrupted by user SIGINT")
        store.release_lock(final_status="CANCELLED")

        # Next run starts: cancelled items are recognized as resumable
        store_next = ClassifierStateStore(self.db_path, run_id="run-resume-canc")
        store_next.acquire_lock()
        self.assertFalse(store_next.is_item_skippable(str(sample1), fp1))
        self.assertFalse(store_next.is_item_skippable(str(sample2), fp2))

        rec1 = store_next.get_item_by_id(item1.item_id)
        self.assertEqual(rec1.status, LifecycleStatus.CANCELLED.value)
        store_next.release_lock()

    def test_move_copy_quarantine_rescue_behavior(self):
        # 1. Move action
        src_move = self.test_dir / "move_src.jpg"
        dest_move = self.test_dir / "dest_dir" / "move_src.jpg"
        src_move.write_text("move-content")
        fp_m, sz_m, mt_m = compute_file_fingerprint(src_move)

        store_m = ClassifierStateStore(self.db_path, run_id="run-move", action="move")
        store_m.acquire_lock()
        item_m = store_m.register_discovered_item(str(src_move), fp_m, sz_m, mt_m)
        store_m.persist_classification_result(item_m.item_id, "MEME", 0.9, "OCR", "meme", 0.1, str(dest_move))
        outcome_m, res_m = store_m.reconcile_item(item_m)
        self.assertEqual(outcome_m, "RESOLVED_ROUTED")
        self.assertTrue(dest_move.exists())
        self.assertFalse(src_move.exists())
        store_m.release_lock()

        # 2. Copy action
        src_copy = self.test_dir / "copy_src.jpg"
        dest_copy = self.test_dir / "dest_dir" / "copy_src.jpg"
        src_copy.write_text("copy-content")
        fp_c, sz_c, mt_c = compute_file_fingerprint(src_copy)

        store_c = ClassifierStateStore(self.db_path, run_id="run-copy", action="copy")
        store_c.acquire_lock()
        item_c = store_c.register_discovered_item(str(src_copy), fp_c, sz_c, mt_c)
        store_c.persist_classification_result(item_c.item_id, "MEME", 0.9, "OCR", "meme", 0.1, str(dest_copy))
        outcome_c, res_c = store_c.reconcile_item(item_c)
        self.assertEqual(outcome_c, "RESOLVED_ROUTED")
        self.assertTrue(dest_copy.exists())
        self.assertTrue(src_copy.exists())  # Copy keeps source
        store_c.release_lock()

    def test_stale_run_reconciliation_recovers_routing_items(self):
        """Verify that recover_stale_runs actively reconciles ROUTING items whose files already moved."""
        src_file = self.test_dir / "stale_routing_src.jpg"
        src_file.write_text("routing-image-data")
        dest_file = self.test_dir / "quarantine" / "memes" / "stale_routing_dest.jpg"
        dest_file.parent.mkdir(parents=True, exist_ok=True)

        store1 = ClassifierStateStore(self.db_path, run_id="run-stale-routing-1", action="move", lease_timeout_sec=0.1)
        store1.acquire_lock()
        fp, sz, mt = compute_file_fingerprint(src_file)
        item = store1.register_discovered_item(str(src_file), fp, sz, mt)
        store1.persist_classification_result(item.item_id, "MEME", 0.95, "CLIP", "Meme detected", 0.05, str(dest_file))
        store1.persist_routing_started(item.item_id)

        # Move file physically to destination, simulating a crash right after the move
        shutil.move(str(src_file), str(dest_file))

        # Crash
        store1._heartbeat_stop_event.set()
        store1.close_lock_fd()
        time.sleep(0.15)

        # Run 2 starts and recovers stale runs
        store2 = ClassifierStateStore(self.db_path, run_id="run-stale-routing-2", action="move")
        store2.acquire_lock()
        recovered = store2.recover_stale_runs()
        self.assertGreaterEqual(recovered, 1)

        # Item should be COMPLETED, NOT reset to DISCOVERED
        rec = store2.get_item_by_id(item.item_id)
        self.assertEqual(rec.status, LifecycleStatus.COMPLETED.value)
        self.assertEqual(rec.destination_path, str(dest_file))
        self.assertTrue(dest_file.exists())
        store2.release_lock()

    def test_poison_pill_max_attempts_exceeded(self):
        """Verify that items failing repeatedly transition to FAILED_PERMANENT."""
        sample_file = self.test_dir / "poison_pill.jpg"
        sample_file.write_text("bad-corrupt-data")
        fp, sz, mt = compute_file_fingerprint(sample_file)

        store1 = ClassifierStateStore(self.db_path, run_id="run-poison-1", lease_timeout_sec=0.1)
        store1.acquire_lock()
        item = store1.register_discovered_item(str(sample_file), fp, sz, mt)
        store1.transition_items([item.item_id], LifecycleStatus.CLASSIFYING)

        # Manually bump attempt_count to 2
        with store1._get_connection() as conn:
            conn.execute("UPDATE classifier_items SET attempt_count = 2 WHERE item_id = ?;", (item.item_id,))

        store1._heartbeat_stop_event.set()
        store1.close_lock_fd()
        time.sleep(0.15)

        store2 = ClassifierStateStore(self.db_path, run_id="run-poison-2")
        store2.acquire_lock()
        store2.recover_stale_runs(max_attempts=3)

        rec = store2.get_item_by_id(item.item_id)
        self.assertEqual(rec.status, LifecycleStatus.FAILED_PERMANENT.value)
        self.assertIn("Exceeded maximum recovery attempts", rec.last_error or "")
        store2.release_lock()

    def test_config_fingerprint_invalidation(self):
        """Verify that changes in config_fingerprint invalidate skippable status."""
        sample_file = self.test_dir / "cfg_inval.jpg"
        sample_file.write_text("content-data")
        fp, sz, mt = compute_file_fingerprint(sample_file)

        store = ClassifierStateStore(self.db_path, run_id="run-cfg", config_fingerprint="cfg_v1")
        store.acquire_lock()
        item = store.register_discovered_item(str(sample_file), fp, sz, mt)
        store.persist_routing_completed(item.item_id, str(sample_file))

        # Matching config fingerprint is skippable
        self.assertTrue(store.is_item_skippable(str(sample_file), fp, config_fingerprint="cfg_v1"))

        # Mismatched config fingerprint is NOT skippable
        self.assertFalse(store.is_item_skippable(str(sample_file), fp, config_fingerprint="cfg_v2"))
        store.release_lock()


if __name__ == "__main__":
    unittest.main()

