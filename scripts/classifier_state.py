"""
scripts/classifier_state.py

Authoritative, crash-safe progress-state store and reconciliation engine
for the media-extractor classification pipeline.
"""

from __future__ import annotations

import argparse
import csv
import enum
import errno
import hashlib
import json
import os
import platform
import re
import shutil
import socket
import sqlite3
import sys
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Generator, List, Optional, Set, Tuple

try:
    import fcntl
except ImportError:
    fcntl = None  # Windows fallback


class LifecycleStatus(str, enum.Enum):
    DISCOVERED = "DISCOVERED"
    CLAIMED = "CLAIMED"
    CLASSIFYING = "CLASSIFYING"
    CLASSIFIED = "CLASSIFIED"
    REVIEW_PENDING = "REVIEW_PENDING"
    ROUTING = "ROUTING"
    COMPLETED = "COMPLETED"
    FAILED_RETRYABLE = "FAILED_RETRYABLE"
    FAILED_PERMANENT = "FAILED_PERMANENT"
    CANCELLED = "CANCELLED"


SUPPORTED_PHOTO_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tiff", ".tif",
    ".heic", ".heif", ".avif", ".dng", ".cr2", ".nef", ".arw",
    ".gif", ".raw"
}


@dataclass
class DiscoverySummary:
    newly_extracted: int
    filesystem_discovered: int
    database_resumed: int
    staging_recovery: int
    already_completed: int
    review_pending: int
    final_candidates_count: int
    completed_percentage: float
    remaining_percentage: float
    cpu_cores: int
    estimated_seconds_remaining: int
    estimated_time_formatted: str
    candidate_paths: List[str]
    seconds_per_image: float = 3.8
    rate_source: str = "calibrated_baseline"

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def format_duration(seconds: int) -> str:
    if seconds <= 0:
        return "< 1s"
    if seconds < 10:
        return "< 10s"
    if seconds < 60:
        return f"{seconds}s"
    minutes = seconds // 60
    rem_sec = seconds % 60
    if minutes < 60:
        if rem_sec > 0:
            return f"{minutes}m {rem_sec}s"
        return f"{minutes}m"
    hours = minutes // 60
    rem_min = minutes % 60
    if rem_min > 0:
        return f"{hours}h {rem_min}m"
    return f"{hours}h"


DEFAULT_CALIBRATED_SECONDS_PER_IMAGE = 3.8


def estimate_processing_time(
    item_count: int,
    cpu_cores: Optional[int] = None,
    measured_seconds_per_image: Optional[float] = None,
) -> Tuple[int, str, float, str]:
    """
    Estimates total processing time based on actual measured processing time per image
    when available in SQLite state, otherwise falling back to calibrated multi-modal inference baseline (~3.8s/img).
    Returns: (eta_seconds, formatted_eta, seconds_per_image, rate_source).
    """
    if item_count <= 0:
        return 0, "< 1s", DEFAULT_CALIBRATED_SECONDS_PER_IMAGE, "calibrated_baseline"

    cores = cpu_cores if cpu_cores and cpu_cores > 0 else (os.cpu_count() or 4)
    if measured_seconds_per_image is not None and measured_seconds_per_image > 0:
        sec_per_img = measured_seconds_per_image
        source = "measured_history"
    else:
        # Calibrated default for CLIP + EasyOCR + Gemini fallback on CPU (~3.5-4.2s per image)
        sec_per_img = max(2.0, min(5.0, DEFAULT_CALIBRATED_SECONDS_PER_IMAGE - (min(cores, 16) - 4) * 0.05))
        source = "calibrated_baseline"

    eta_sec = int(round(item_count * sec_per_img))
    return eta_sec, format_duration(eta_sec), round(sec_per_img, 2), source


RESUMABLE_STATUSES = {
    LifecycleStatus.DISCOVERED.value,
    LifecycleStatus.CLAIMED.value,
    LifecycleStatus.CLASSIFYING.value,
    LifecycleStatus.ROUTING.value,
    LifecycleStatus.FAILED_RETRYABLE.value,
    LifecycleStatus.CANCELLED.value,
}

TERMINAL_STATUSES = {
    LifecycleStatus.COMPLETED.value,
    LifecycleStatus.FAILED_PERMANENT.value,
}


class ClassifierLockError(RuntimeError):
    """Raised when another live classifier process holds the state lock."""
    pass


def current_iso_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


def compute_file_fingerprint(path: Path, strategy: str = "cheap") -> Tuple[str, int, int]:
    """
    Computes a fast, durable fingerprint for a file without full hashing by default.
    Returns (fingerprint_string, file_size, mtime_ns).
    Cheap strategy: size + mtime_ns + SHA-256 of first 4KB and last 4KB.
    """
    resolved = path.resolve()
    stat = resolved.stat()
    file_size = stat.st_size
    mtime_ns = getattr(stat, "st_mtime_ns", int(stat.st_mtime * 1e9))

    if strategy == "full-sha256":
        full_hash = compute_sha256(resolved)
        fingerprint = f"{file_size}_{mtime_ns}_{full_hash}"
        return fingerprint, file_size, mtime_ns

    hasher = hashlib.sha256()
    hasher.update(f"{file_size}:{mtime_ns}:".encode("utf-8"))
    with open(resolved, "rb") as f:
        head = f.read(4096)
        hasher.update(head)
        if file_size > 8192:
            f.seek(file_size - 4096)
            tail = f.read(4096)
            hasher.update(tail)
        elif file_size > 4096:
            tail = f.read(file_size - 4096)
            hasher.update(tail)

    sparse_digest = hasher.hexdigest()
    fingerprint = f"{file_size}_{mtime_ns}_{sparse_digest[:16]}"
    return fingerprint, file_size, mtime_ns


def compute_sha256(path: Path) -> str:
    """Computes full SHA-256 hash of a file."""
    hasher = hashlib.sha256()
    with open(path.resolve(), "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def compute_item_id(canonical_path: str) -> str:
    """Generates a stable item_id from the canonical path."""
    return hashlib.sha256(canonical_path.strip().encode("utf-8")).hexdigest()[:32]


@dataclass
class ItemRecord:
    item_id: str
    canonical_path: str
    original_path: Optional[str]
    staged_path: Optional[str]
    file_size: int
    mtime_ns: int
    fingerprint: str
    content_hash: Optional[str]
    status: str
    category: Optional[str]
    confidence: Optional[float]
    tier: Optional[str]
    decision_reason: Optional[str]
    uncertainty: Optional[float]
    intended_destination: Optional[str]
    destination_path: Optional[str]
    routing_action: Optional[str]
    model_version: Optional[str]
    config_fingerprint: Optional[str]
    run_id: Optional[str]
    attempt_count: int
    last_error: Optional[str]
    claimed_by_run_id: Optional[str]
    claimed_at: Optional[str]
    lease_expires_at: Optional[float]
    created_at: str
    updated_at: str

    @classmethod
    def from_row(cls, row: sqlite3.Row) -> ItemRecord:
        return cls(
            item_id=row["item_id"],
            canonical_path=row["canonical_path"],
            original_path=row["original_path"],
            staged_path=row["staged_path"],
            file_size=row["file_size"],
            mtime_ns=row["mtime_ns"],
            fingerprint=row["fingerprint"],
            content_hash=row["content_hash"],
            status=row["status"],
            category=row["category"],
            confidence=row["confidence"],
            tier=row["tier"],
            decision_reason=row["decision_reason"],
            uncertainty=row["uncertainty"],
            intended_destination=row["intended_destination"],
            destination_path=row["destination_path"],
            routing_action=row["routing_action"],
            model_version=row["model_version"],
            config_fingerprint=row["config_fingerprint"],
            run_id=row["run_id"],
            attempt_count=row["attempt_count"],
            last_error=row["last_error"],
            claimed_by_run_id=row["claimed_by_run_id"],
            claimed_at=row["claimed_at"],
            lease_expires_at=row["lease_expires_at"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )


class ClassifierStateStore:
    """
    Authoritative SQLite progress-state manager with WAL mode, single-process locking,
    stale lease recovery, two-phase commits, and reconciliation.
    """

    def __init__(
        self,
        db_path: Optional[Path] = None,
        run_id: Optional[str] = None,
        lease_timeout_sec: float = 300.0,
        config_fingerprint: str = "",
        model_version: str = "",
        action: str = "move",
        quarantine: bool = True,
        rescue: bool = False,
        mode: str = "default",
        command_line: str = "",
    ):
        if db_path is None:
            self.db_path = (Path.home() / "archive" / "memories" / ".classifier-state" / "classification.sqlite3").resolve()
        else:
            self.db_path = db_path.resolve()
        self.run_id = run_id or f"{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"
        self.lease_timeout_sec = max(0.05, float(lease_timeout_sec))
        self.config_fingerprint = config_fingerprint
        self.model_version = model_version
        self.action = action
        self.quarantine = quarantine
        self.rescue = rescue
        self.mode = mode
        self.command_line = command_line

        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.lock_file_path = self.db_path.with_suffix(".sqlite3.lock")
        self._lock_fd: Optional[int] = None

        self._heartbeat_stop_event = threading.Event()
        self._heartbeat_thread: Optional[threading.Thread] = None

        self._init_db()

    def close_lock_fd(self):
        """Simulates process exit for lock testing by closing the flock file descriptor."""
        if self._lock_fd is not None:
            try:
                if fcntl is not None:
                    fcntl.flock(self._lock_fd, fcntl.LOCK_UN)
                os.close(self._lock_fd)
            except Exception:
                pass
            self._lock_fd = None

    @contextmanager
    def _get_connection(self) -> Generator[sqlite3.Connection, None, None]:
        conn = sqlite3.connect(str(self.db_path), timeout=30.0, isolation_level=None)
        conn.row_factory = sqlite3.Row
        try:
            conn.execute("PRAGMA journal_mode = WAL;")
            conn.execute("PRAGMA synchronous = NORMAL;")
            conn.execute("PRAGMA busy_timeout = 30000;")
            conn.execute("PRAGMA foreign_keys = ON;")
            yield conn
        finally:
            conn.close()

    def _init_db(self):
        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS classifier_runs (
                    run_id TEXT PRIMARY KEY,
                    pid INTEGER NOT NULL,
                    hostname TEXT NOT NULL,
                    command_line TEXT,
                    action TEXT,
                    quarantine INTEGER,
                    rescue INTEGER,
                    mode TEXT,
                    config_fingerprint TEXT,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    heartbeat_at TEXT NOT NULL,
                    ended_at TEXT,
                    error_message TEXT
                );
            """)

            conn.execute("""
                CREATE TABLE IF NOT EXISTS classifier_items (
                    item_id TEXT PRIMARY KEY,
                    canonical_path TEXT NOT NULL,
                    original_path TEXT,
                    staged_path TEXT,
                    file_size INTEGER NOT NULL,
                    mtime_ns INTEGER NOT NULL,
                    fingerprint TEXT NOT NULL,
                    content_hash TEXT,
                    status TEXT NOT NULL,
                    category TEXT,
                    confidence REAL,
                    tier TEXT,
                    decision_reason TEXT,
                    uncertainty REAL,
                    intended_destination TEXT,
                    destination_path TEXT,
                    routing_action TEXT,
                    model_version TEXT,
                    config_fingerprint TEXT,
                    run_id TEXT REFERENCES classifier_runs(run_id),
                    attempt_count INTEGER DEFAULT 0,
                    last_error TEXT,
                    claimed_by_run_id TEXT,
                    claimed_at TEXT,
                    lease_expires_at REAL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
            """)

            conn.execute("CREATE INDEX IF NOT EXISTS idx_items_canonical_path ON classifier_items(canonical_path);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_items_fingerprint ON classifier_items(fingerprint);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_items_status ON classifier_items(status);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_items_run_id ON classifier_items(run_id);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_items_staged_path ON classifier_items(staged_path);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_items_original_path ON classifier_items(original_path);")

            conn.execute("""
                CREATE TABLE IF NOT EXISTS classifier_transitions (
                    transition_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id TEXT NOT NULL,
                    from_status TEXT,
                    to_status TEXT NOT NULL,
                    run_id TEXT,
                    message TEXT,
                    timestamp TEXT NOT NULL
                );
            """)

            conn.execute("""
                CREATE TABLE IF NOT EXISTS classifier_locks (
                    lock_id INTEGER PRIMARY KEY CHECK (lock_id = 1),
                    run_id TEXT NOT NULL,
                    pid INTEGER NOT NULL,
                    hostname TEXT NOT NULL,
                    acquired_at TEXT NOT NULL,
                    heartbeat_at TEXT NOT NULL,
                    lease_timeout_sec REAL NOT NULL
                );
            """)
            conn.execute("COMMIT;")

    # -------------------------------------------------------------------------
    # Single-Process Locking & Heartbeats
    # -------------------------------------------------------------------------

    @staticmethod
    def _is_pid_alive(pid: int) -> bool:
        if pid <= 0:
            return False
        try:
            os.kill(pid, 0)
            return True
        except OSError as err:
            return err.errno == errno.EPERM

    def acquire_lock(self, force: bool = False):
        """
        Acquires exclusive process lock.
        Uses both filesystem lock and SQLite lock table with liveness & lease validation.
        """
        if fcntl is not None:
            try:
                self._lock_fd = os.open(str(self.lock_file_path), os.O_CREAT | os.O_RDWR, 0o644)
                fcntl.flock(self._lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except (BlockingIOError, OSError) as e:
                if not force:
                    raise ClassifierLockError(
                        f"Cannot acquire flock on {self.lock_file_path}: lock held by another active process"
                    ) from e

        with self._get_connection() as conn:
            try:
                conn.execute("BEGIN IMMEDIATE;")
                cur = conn.execute("SELECT * FROM classifier_locks WHERE lock_id = 1;")
                lock_row = cur.fetchone()

                current_host = socket.gethostname()
                current_pid = os.getpid()
                now_iso = current_iso_timestamp()
                now_ts = time.time()

                if lock_row and not force:
                    lock_run_id = lock_row["run_id"]
                    lock_pid = lock_row["pid"]
                    lock_host = lock_row["hostname"]
                    heartbeat_str = lock_row["heartbeat_at"]
                    lease_sec = lock_row["lease_timeout_sec"]

                    try:
                        hb_dt = datetime.fromisoformat(heartbeat_str)
                        hb_ts = hb_dt.timestamp()
                    except Exception:
                        hb_ts = 0.0

                    lease_expired = (now_ts - hb_ts) > lease_sec
                    is_same_host = (lock_host == current_host)
                    is_different_run = (lock_run_id != self.run_id)

                    # A process is considered live if:
                    # 1. It is a different run
                    # 2. On the same host:
                    #    - if different PID: os.kill says it's alive (lease expiration never overrides a living local PID)
                    #    - if same PID: lease hasn't expired (e.g. simulated in-process test runs)
                    # 3. On different host: lease hasn't expired.
                    pid_active = False
                    if is_different_run:
                        if is_same_host:
                            if lock_pid != current_pid:
                                pid_active = self._is_pid_alive(lock_pid)
                            else:
                                pid_active = not lease_expired
                        else:
                            pid_active = not lease_expired

                    if pid_active:
                        raise ClassifierLockError(
                            f"Another classifier process is actively running: PID={lock_pid} on {lock_host} "
                            f"(run_id={lock_run_id}, last heartbeat={heartbeat_str})."
                        )

                    # Mark stale run abandoned
                    conn.execute(
                        "UPDATE classifier_runs SET status = 'ABANDONED', ended_at = ? WHERE run_id = ? AND status = 'RUNNING';",
                        (now_iso, lock_run_id)
                    )

                conn.execute("""
                    INSERT INTO classifier_locks (lock_id, run_id, pid, hostname, acquired_at, heartbeat_at, lease_timeout_sec)
                    VALUES (1, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(lock_id) DO UPDATE SET
                        run_id = excluded.run_id,
                        pid = excluded.pid,
                        hostname = excluded.hostname,
                        acquired_at = excluded.acquired_at,
                        heartbeat_at = excluded.heartbeat_at,
                        lease_timeout_sec = excluded.lease_timeout_sec;
                """, (self.run_id, current_pid, current_host, now_iso, now_iso, self.lease_timeout_sec))

                conn.execute("""
                    INSERT INTO classifier_runs (
                        run_id, pid, hostname, command_line, action, quarantine, rescue,
                        mode, config_fingerprint, status, started_at, heartbeat_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
                    ON CONFLICT(run_id) DO UPDATE SET
                        status = 'RUNNING',
                        heartbeat_at = excluded.heartbeat_at;
                """, (
                    self.run_id, current_pid, current_host, self.command_line,
                    self.action, int(self.quarantine), int(self.rescue),
                    self.mode, self.config_fingerprint, now_iso, now_iso
                ))

                conn.execute("COMMIT;")
            except Exception:
                try:
                    conn.execute("ROLLBACK;")
                except Exception:
                    pass
                raise

        self._start_heartbeat_thread()

    def _start_heartbeat_thread(self):
        interval = max(0.5, self.lease_timeout_sec / 3.0)

        def _heartbeat_loop():
            while not self._heartbeat_stop_event.wait(interval):
                try:
                    self.heartbeat()
                except Exception:
                    pass

        self._heartbeat_thread = threading.Thread(target=_heartbeat_loop, name="classifier-heartbeat", daemon=True)
        self._heartbeat_thread.start()

    def heartbeat(self):
        """Updates the heartbeat timestamp for current run and lock."""
        now_iso = current_iso_timestamp()
        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            conn.execute(
                "UPDATE classifier_locks SET heartbeat_at = ? WHERE lock_id = 1 AND run_id = ?;",
                (now_iso, self.run_id)
            )
            conn.execute(
                "UPDATE classifier_runs SET heartbeat_at = ? WHERE run_id = ? AND status = 'RUNNING';",
                (now_iso, self.run_id)
            )
            conn.execute("COMMIT;")

    def release_lock(self, final_status: str = "COMPLETED", error_message: Optional[str] = None):
        """Releases process lock and updates run record."""
        self._heartbeat_stop_event.set()
        if self._heartbeat_thread and self._heartbeat_thread.is_alive():
            self._heartbeat_thread.join(timeout=2.0)

        now_iso = current_iso_timestamp()
        try:
            with self._get_connection() as conn:
                conn.execute("BEGIN IMMEDIATE;")
                conn.execute(
                    "DELETE FROM classifier_locks WHERE lock_id = 1 AND run_id = ?;",
                    (self.run_id,)
                )
                conn.execute("""
                    UPDATE classifier_runs
                    SET status = ?, ended_at = ?, error_message = ?
                    WHERE run_id = ?;
                """, (final_status, now_iso, error_message, self.run_id))
                conn.execute("COMMIT;")
        except Exception:
            pass

        if self._lock_fd is not None:
            try:
                if fcntl is not None:
                    fcntl.flock(self._lock_fd, fcntl.LOCK_UN)
                os.close(self._lock_fd)
            except Exception:
                pass
            self._lock_fd = None

    # -------------------------------------------------------------------------
    # Stale Run Recovery & In-flight Reconciliation
    # -------------------------------------------------------------------------

    def recover_stale_runs(self, max_attempts: int = 3) -> int:
        """
        Scans for abandoned or dead runs and recovers their in-flight items
        (CLAIMED, CLASSIFYING, CLASSIFIED, ROUTING) using active reconciliation.
        """
        current_host = socket.gethostname()
        current_pid = os.getpid()
        now_ts = time.time()
        now_iso = current_iso_timestamp()
        recovered_count = 0

        candidate_items: List[ItemRecord] = []

        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            cur = conn.execute("""
                SELECT * FROM classifier_runs
                WHERE (status = 'RUNNING' OR status = 'ABANDONED' OR status = 'CANCELLED') AND run_id != ?;
            """, (self.run_id,))
            candidate_runs = cur.fetchall()

            for run in candidate_runs:
                r_id = run["run_id"]
                r_pid = run["pid"]
                r_host = run["hostname"]
                r_hb = run["heartbeat_at"]
                r_status = run["status"]

                try:
                    hb_ts = datetime.fromisoformat(r_hb).timestamp()
                except Exception:
                    hb_ts = 0.0

                is_same_host = (r_host == current_host)
                is_expired = (now_ts - hb_ts) > self.lease_timeout_sec
                is_dead = (is_same_host and r_pid != current_pid and not self._is_pid_alive(r_pid))

                if r_status in ("ABANDONED", "CANCELLED") or is_dead or is_expired:
                    if r_status != "ABANDONED" and r_status != "CANCELLED":
                        conn.execute(
                            "UPDATE classifier_runs SET status = 'ABANDONED', ended_at = ? WHERE run_id = ?;",
                            (now_iso, r_id)
                        )
                    item_cur = conn.execute("""
                        SELECT * FROM classifier_items
                        WHERE run_id = ? AND status IN ('CLAIMED', 'CLASSIFYING', 'CLASSIFIED', 'ROUTING', 'CANCELLED', 'FAILED_RETRYABLE');
                    """, (r_id,))
                    candidate_items.extend([ItemRecord.from_row(r) for r in item_cur.fetchall()])

            conn.execute("COMMIT;")

        # Active reconciliation and poison-pill limits handled per-item
        for item in candidate_items:
            # 1. If item was in ROUTING, CLASSIFIED, CANCELLED, or FAILED_RETRYABLE with intended destination, actively reconcile
            if item.status in (LifecycleStatus.ROUTING.value, LifecycleStatus.CLASSIFIED.value, LifecycleStatus.CANCELLED.value, LifecycleStatus.FAILED_RETRYABLE.value) and item.intended_destination:
                outcome, res_path = self.reconcile_item(item)

                if outcome in ("RESOLVED_DEST_EXISTS", "RESOLVED_BOTH_MATCH", "RESOLVED_ROUTED"):
                    recovered_count += 1
                    continue
                if outcome == "FAILED_MISSING":
                    recovered_count += 1
                    continue

            # 2. Poison-pill protection: cap retries to avoid infinite crash loops
            if item.attempt_count + 1 >= max_attempts:
                self.mark_failed(item.item_id, f"Exceeded maximum recovery attempts ({max_attempts})", retryable=False)
                recovered_count += 1
                continue

            # 3. Reset to DISCOVERED for clean retry
            with self._get_connection() as conn:
                conn.execute("BEGIN IMMEDIATE;")
                new_status = LifecycleStatus.DISCOVERED.value
                conn.execute("""
                    UPDATE classifier_items
                    SET status = ?, claimed_by_run_id = NULL, lease_expires_at = NULL,
                        attempt_count = attempt_count + 1, updated_at = ?
                    WHERE item_id = ?;
                """, (new_status, now_iso, item.item_id))

                conn.execute("""
                    INSERT INTO classifier_transitions (item_id, from_status, to_status, run_id, message, timestamp)
                    VALUES (?, ?, ?, ?, ?, ?);
                """, (item.item_id, item.status, new_status, self.run_id, f"Recovered from abandoned run {item.run_id}", now_iso))
                conn.execute("COMMIT;")
                recovered_count += 1

        return recovered_count

    # -------------------------------------------------------------------------
    # Item Registration & Querying
    # -------------------------------------------------------------------------

    def get_item_by_path(self, canonical_path: str) -> Optional[ItemRecord]:
        with self._get_connection() as conn:
            cur = conn.execute("SELECT * FROM classifier_items WHERE canonical_path = ?;", (canonical_path,))
            row = cur.fetchone()
            return ItemRecord.from_row(row) if row else None

    def get_item_by_id(self, item_id: str) -> Optional[ItemRecord]:
        with self._get_connection() as conn:
            cur = conn.execute("SELECT * FROM classifier_items WHERE item_id = ?;", (item_id,))
            row = cur.fetchone()
            return ItemRecord.from_row(row) if row else None

    def find_item_by_fingerprint_or_path(self, canonical_path: str, fingerprint: str) -> Optional[ItemRecord]:
        """
        Finds existing item by canonical path first, then by fingerprint (to detect renames).
        """
        with self._get_connection() as conn:
            cur = conn.execute("SELECT * FROM classifier_items WHERE canonical_path = ?;", (canonical_path,))
            row = cur.fetchone()
            if row:
                return ItemRecord.from_row(row)

            cur = conn.execute("SELECT * FROM classifier_items WHERE fingerprint = ?;", (fingerprint,))
            row = cur.fetchone()
            if row:
                return ItemRecord.from_row(row)
        return None

    def is_item_skippable(self, canonical_path: str, fingerprint: str, config_fingerprint: Optional[str] = None) -> bool:
        """
        Determines whether a file can be safely skipped on resume.
        A file is skippable ONLY if:
        1. It is durably recorded with status COMPLETED (or REVIEW_PENDING).
        2. Its fingerprint matches the current file.
        3. Its config/model matches (if specified).
        """
        item = self.get_item_by_path(canonical_path)
        if not item:
            with self._get_connection() as conn:
                cur = conn.execute("SELECT * FROM classifier_items WHERE fingerprint = ?;", (fingerprint,))
                row = cur.fetchone()
                if row:
                    item = ItemRecord.from_row(row)

        if not item:
            return False

        if item.status not in (LifecycleStatus.COMPLETED.value, LifecycleStatus.REVIEW_PENDING.value):
            return False

        if item.fingerprint != fingerprint:
            return False

        if config_fingerprint:
            if not item.config_fingerprint or item.config_fingerprint != config_fingerprint:
                return False

        return True

    def register_discovered_item(
        self,
        canonical_path: str,
        fingerprint: str,
        file_size: int,
        mtime_ns: int,
        original_path: Optional[str] = None,
        staged_path: Optional[str] = None,
    ) -> ItemRecord:
        """Registers a discovered item in the store or updates an existing item."""
        item_id = compute_item_id(canonical_path)
        now_iso = current_iso_timestamp()

        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            cur = conn.execute("SELECT * FROM classifier_items WHERE item_id = ?;", (item_id,))
            existing = cur.fetchone()

            if existing:
                rec = ItemRecord.from_row(existing)
                if rec.fingerprint != fingerprint or rec.file_size != file_size:
                    conn.execute("""
                        UPDATE classifier_items SET
                            fingerprint = ?, file_size = ?, mtime_ns = ?,
                            original_path = COALESCE(?, original_path),
                            staged_path = COALESCE(?, staged_path),
                            status = 'DISCOVERED',
                            category = NULL, confidence = NULL, tier = NULL,
                            decision_reason = NULL, uncertainty = NULL,
                            intended_destination = NULL, destination_path = NULL,
                            run_id = ?, updated_at = ?
                        WHERE item_id = ?;
                    """, (fingerprint, file_size, mtime_ns, original_path, staged_path, self.run_id, now_iso, item_id))
                    conn.execute("""
                        INSERT INTO classifier_transitions (item_id, from_status, to_status, run_id, message, timestamp)
                        VALUES (?, ?, 'DISCOVERED', ?, 'File modified on disk; reset for reclassification', ?);
                    """, (item_id, rec.status, self.run_id, now_iso))
            else:
                conn.execute("""
                    INSERT INTO classifier_items (
                        item_id, canonical_path, original_path, staged_path,
                        file_size, mtime_ns, fingerprint, status,
                        model_version, config_fingerprint, run_id, attempt_count,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'DISCOVERED', ?, ?, ?, 0, ?, ?);
                """, (
                    item_id, canonical_path, original_path, staged_path,
                    file_size, mtime_ns, fingerprint,
                    self.model_version, self.config_fingerprint, self.run_id,
                    now_iso, now_iso
                ))
                conn.execute("""
                    INSERT INTO classifier_transitions (item_id, from_status, to_status, run_id, message, timestamp)
                    VALUES (?, NULL, 'DISCOVERED', ?, 'Discovered item', ?);
                """, (item_id, self.run_id, now_iso))

            conn.execute("COMMIT;")

        return self.get_item_by_id(item_id)

    # -------------------------------------------------------------------------
    # State Transitions & Two-Phase Lifecycle
    # -------------------------------------------------------------------------

    def transition_items(
        self,
        item_ids: List[str],
        to_status: LifecycleStatus,
        message: Optional[str] = None,
        lease_duration_sec: Optional[float] = None,
    ):
        """Transitions multiple items to a new status atomically."""
        if not item_ids:
            return

        now_iso = current_iso_timestamp()
        lease_expires_at = (time.time() + lease_duration_sec) if lease_duration_sec else None

        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            for i_id in item_ids:
                cur = conn.execute("SELECT status FROM classifier_items WHERE item_id = ?;", (i_id,))
                row = cur.fetchone()
                from_status = row["status"] if row else None

                conn.execute("""
                    UPDATE classifier_items SET
                        status = ?,
                        claimed_by_run_id = ?,
                        claimed_at = CASE WHEN ? = 'CLAIMED' THEN ? ELSE claimed_at END,
                        lease_expires_at = ?,
                        run_id = ?,
                        updated_at = ?
                    WHERE item_id = ?;
                """, (
                    to_status.value, self.run_id, to_status.value, now_iso,
                    lease_expires_at, self.run_id, now_iso, i_id
                ))

                conn.execute("""
                    INSERT INTO classifier_transitions (item_id, from_status, to_status, run_id, message, timestamp)
                    VALUES (?, ?, ?, ?, ?, ?);
                """, (i_id, from_status, to_status.value, self.run_id, message, now_iso))

            conn.execute("COMMIT;")

    def persist_classification_result(
        self,
        item_id: str,
        category: str,
        confidence: float,
        tier: str,
        decision_reason: str,
        uncertainty: float,
        intended_destination: Optional[str],
        needs_review: bool = False,
    ):
        """
        Phase 1 of Two-Phase Routing:
        Persists classification decision & intended destination *before* filesystem operations.
        Status becomes CLASSIFIED (or REVIEW_PENDING).
        """
        now_iso = current_iso_timestamp()
        target_status = LifecycleStatus.REVIEW_PENDING.value if needs_review else LifecycleStatus.CLASSIFIED.value

        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            cur = conn.execute("SELECT status FROM classifier_items WHERE item_id = ?;", (item_id,))
            row = cur.fetchone()
            from_status = row["status"] if row else None

            conn.execute("""
                UPDATE classifier_items SET
                    category = ?,
                    confidence = ?,
                    tier = ?,
                    decision_reason = ?,
                    uncertainty = ?,
                    intended_destination = ?,
                    routing_action = ?,
                    model_version = ?,
                    config_fingerprint = ?,
                    status = ?,
                    run_id = ?,
                    updated_at = ?
                WHERE item_id = ?;
            """, (
                category, confidence, tier, decision_reason, uncertainty,
                intended_destination, self.action, self.model_version,
                self.config_fingerprint, target_status, self.run_id, now_iso,
                item_id
            ))

            conn.execute("""
                INSERT INTO classifier_transitions (item_id, from_status, to_status, run_id, message, timestamp)
                VALUES (?, ?, ?, ?, 'Persisted classification result', ?);
            """, (item_id, from_status, target_status, self.run_id, now_iso))

            conn.execute("COMMIT;")

    def persist_routing_started(self, item_id: str):
        """Marks item as actively ROUTING."""
        self.transition_items([item_id], LifecycleStatus.ROUTING, message="Starting filesystem routing")

    def persist_routing_completed(
        self,
        item_id: str,
        destination_path: Optional[str],
        content_hash: Optional[str] = None,
    ):
        """
        Phase 2 of Two-Phase Routing:
        Marks filesystem routing as COMPLETED with the verified destination path.
        """
        now_iso = current_iso_timestamp()
        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            cur = conn.execute("SELECT status FROM classifier_items WHERE item_id = ?;", (item_id,))
            row = cur.fetchone()
            from_status = row["status"] if row else None

            conn.execute("""
                UPDATE classifier_items SET
                    status = 'COMPLETED',
                    destination_path = ?,
                    content_hash = COALESCE(?, content_hash),
                    lease_expires_at = NULL,
                    claimed_by_run_id = NULL,
                    updated_at = ?
                WHERE item_id = ?;
            """, (destination_path, content_hash, now_iso, item_id))

            conn.execute("""
                INSERT INTO classifier_transitions (item_id, from_status, to_status, run_id, message, timestamp)
                VALUES (?, ?, 'COMPLETED', ?, ?, ?);
            """, (item_id, from_status, self.run_id, f"Routed to {destination_path}", now_iso))

            conn.execute("COMMIT;")

    def mark_failed(self, item_id: str, error_message: str, retryable: bool = True):
        """Records failure on an item."""
        now_iso = current_iso_timestamp()
        target_status = LifecycleStatus.FAILED_RETRYABLE.value if retryable else LifecycleStatus.FAILED_PERMANENT.value

        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            cur = conn.execute("SELECT status FROM classifier_items WHERE item_id = ?;", (item_id,))
            row = cur.fetchone()
            from_status = row["status"] if row else None

            conn.execute("""
                UPDATE classifier_items SET
                    status = ?,
                    last_error = ?,
                    attempt_count = attempt_count + 1,
                    lease_expires_at = NULL,
                    claimed_by_run_id = NULL,
                    updated_at = ?
                WHERE item_id = ?;
            """, (target_status, error_message, now_iso, item_id))

            conn.execute("""
                INSERT INTO classifier_transitions (item_id, from_status, to_status, run_id, message, timestamp)
                VALUES (?, ?, ?, ?, ?, ?);
            """, (item_id, from_status, target_status, self.run_id, f"Error: {error_message}", now_iso))

            conn.execute("COMMIT;")

    # -------------------------------------------------------------------------
    # Reconciliation Engine (7 Crash Cases)
    # -------------------------------------------------------------------------

    def reconcile_item(self, item: ItemRecord, base_memories_dir: Optional[Path] = None) -> Tuple[str, Optional[Path]]:
        """
        Reconciles an item whose processing was interrupted.
        Always refreshes item from DB to ensure latest fields.
        """
        refreshed = self.get_item_by_id(item.item_id)
        if refreshed:
            item = refreshed

        source_path = Path(item.canonical_path)
        dest_str = item.intended_destination or item.destination_path
        dest_path = Path(dest_str) if dest_str else None

        source_exists = source_path.exists()
        dest_exists = dest_path.exists() if dest_path else False

        # Case 3: Both source and destination exist
        if source_exists and dest_exists and dest_path is not None:
            src_hash = compute_sha256(source_path)
            dst_hash = compute_sha256(dest_path)
            if src_hash == dst_hash:
                if item.routing_action == "move":
                    try:
                        source_path.unlink()
                    except Exception:
                        pass
                self.persist_routing_completed(item.item_id, str(dest_path), dst_hash)
                return "RESOLVED_BOTH_MATCH", dest_path
            else:
                unique_dest = self.get_collision_safe_dest(dest_path.parent, dest_path.name)
                if item.routing_action == "move":
                    shutil.move(str(source_path), str(unique_dest))
                elif item.routing_action == "copy":
                    shutil.copy2(str(source_path), str(unique_dest))
                self.persist_routing_completed(item.item_id, str(unique_dest))
                return "RESOLVED_COLLISION_UNIQUE", unique_dest

        # Case 2: Source does not exist, but destination exists
        if not source_exists and dest_exists and dest_path is not None:
            # Verify destination integrity: check size matches expected file_size
            try:
                dst_stat = dest_path.stat()
                if item.file_size > 0 and dst_stat.st_size != item.file_size:
                    self.mark_failed(item.item_id, f"Destination size {dst_stat.st_size} does not match expected {item.file_size}", retryable=True)
                    return "FAILED_CORRUPT_DEST", None
            except Exception as e:
                self.mark_failed(item.item_id, f"Cannot stat destination: {e}", retryable=True)
                return "FAILED_DEST_STAT", None

            self.persist_routing_completed(item.item_id, str(dest_path))
            return "RESOLVED_DEST_EXISTS", dest_path

        # Case 1: Source exists, but destination does not exist
        if source_exists and (dest_path is None or not dest_exists):
            if item.status in (LifecycleStatus.CLASSIFIED.value, LifecycleStatus.ROUTING.value) and dest_path:
                dest_path.parent.mkdir(parents=True, exist_ok=True)
                stat = source_path.stat()
                if item.routing_action == "move":
                    shutil.move(str(source_path), str(dest_path))
                elif item.routing_action == "copy":
                    shutil.copy2(str(source_path), str(dest_path))
                try:
                    os.utime(dest_path, (stat.st_atime, stat.st_mtime))
                except Exception:
                    pass
                self.persist_routing_completed(item.item_id, str(dest_path))
                return "RESOLVED_ROUTED", dest_path
            else:
                self.transition_items([item.item_id], LifecycleStatus.DISCOVERED, "Reconciliation: ready for reclassification")
                return "RESET_DISCOVERED", source_path

        # Case 4: Neither source nor destination exists
        if not source_exists and (dest_path is None or not dest_exists):
            if item.staged_path and Path(item.staged_path).exists():
                staged_p = Path(item.staged_path)
                return "STAGED_EXISTS", staged_p
            if item.original_path and Path(item.original_path).exists():
                orig_p = Path(item.original_path)
                return "ORIGINAL_EXISTS", orig_p

            self.mark_failed(item.item_id, "Source and destination files both missing during reconciliation", retryable=True)
            return "FAILED_MISSING", None

        return "UNMODIFIED", None

    @staticmethod
    def get_collision_safe_dest(target_dir: Path, file_name: str) -> Path:
        target_dir.mkdir(parents=True, exist_ok=True)
        direct = target_dir / file_name
        if not direct.exists():
            return direct
        stem = direct.stem
        suffix = direct.suffix
        counter = 1
        while True:
            candidate = target_dir / f"{stem}_{counter}{suffix}"
            if not candidate.exists():
                return candidate
            counter += 1

    # -------------------------------------------------------------------------
    # Legacy CSV Migration & CSV Synchronization
    # -------------------------------------------------------------------------

    def import_legacy_csv(self, csv_path: Path, source_dir: Optional[Path] = None) -> int:
        """
        Imports legacy CSV records into the SQLite database.
        Validates file existence and fingerprint to prevent stale entries.
        Optionally re-bases paths against source_dir if the original absolute path is missing.
        """
        if not csv_path.exists():
            return 0

        imported = 0
        now_iso = current_iso_timestamp()

        try:
            with open(csv_path, mode="r", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                with self._get_connection() as conn:
                    conn.execute("BEGIN IMMEDIATE;")
                    for row in reader:
                        raw_path = row.get("file_path")
                        if not raw_path:
                            continue

                        p = Path(raw_path).expanduser().resolve()
                        if not p.exists() and source_dir:
                            resolved_sd = source_dir.resolve()
                            candidate = resolved_sd / Path(raw_path).name
                            if not candidate.exists():
                                parts = Path(raw_path).parts
                                for i in range(len(parts) - 1, 0, -1):
                                    if re.fullmatch(r"\d{4}", parts[i]):
                                        sub_candidate = resolved_sd.joinpath(*parts[i:])
                                        if sub_candidate.exists():
                                            candidate = sub_candidate
                                            break
                            if candidate.exists():
                                p = candidate

                        if not p.exists():
                            continue

                        try:
                            fp, size, mtime_ns = compute_file_fingerprint(p)
                        except Exception:
                            continue

                        item_id = compute_item_id(str(p))
                        category = row.get("category")
                        confidence = float(row.get("confidence", 0.0) or 0.0)
                        tier = row.get("tier")
                        decision_reason = row.get("decision_reason")
                        uncertainty = float(row.get("uncertainty", 0.0) or 0.0)
                        model_ver = row.get("model_version") or self.model_version
                        relocated_to = row.get("relocated_to") or ""

                        cur = conn.execute("SELECT status FROM classifier_items WHERE item_id = ?;", (item_id,))
                        if cur.fetchone():
                            continue

                        conn.execute("""
                            INSERT INTO classifier_items (
                                item_id, canonical_path, file_size, mtime_ns, fingerprint,
                                status, category, confidence, tier, decision_reason, uncertainty,
                                destination_path, model_version, config_fingerprint, run_id,
                                created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                        """, (
                            item_id, str(p), size, mtime_ns, fp,
                            category, confidence, tier, decision_reason, uncertainty,
                            relocated_to if relocated_to else str(p), model_ver,
                            self.config_fingerprint, self.run_id, now_iso, now_iso
                        ))
                        imported += 1

                    conn.execute("COMMIT;")
        except Exception as e:
            print(f"[WARN] Error importing legacy CSV {csv_path}: {e}")

        return imported

    def export_csv(self, output_csv: Path, review_csv: Optional[Path] = None, header: Optional[List[str]] = None) -> int:
        """
        Exports durable state from SQLite into human-readable CSV files
        atomically using temporary files and atomic replace.
        """
        if header is None:
            header = [
                "file_path", "category", "confidence", "tier", "decision_reason",
                "ocr_text", "ocr_confidence", "text_box_count", "text_area_ratio",
                "clip_top_label", "clip_margin", "greeting_keyword_hits", "meme_keyword_hits",
                "uncertainty", "model_version", "relocated_to", "timestamp"
            ]

        with self._get_connection() as conn:
            cur = conn.execute("""
                SELECT * FROM classifier_items
                WHERE status IN ('COMPLETED', 'REVIEW_PENDING')
                ORDER BY updated_at ASC;
            """)
            rows = [ItemRecord.from_row(r) for r in cur.fetchall()]

        output_csv.parent.mkdir(parents=True, exist_ok=True)
        tmp_output = output_csv.with_suffix(".tmp")
        tmp_review = review_csv.with_suffix(".tmp") if review_csv else None

        written_output = 0
        with open(tmp_output, "w", newline="", encoding="utf-8") as f_out:
            w_out = csv.writer(f_out)
            w_out.writerow(header)

            w_rev = None
            f_rev = None
            if tmp_review:
                f_rev = open(tmp_review, "w", newline="", encoding="utf-8")
                w_rev = csv.writer(f_rev)
                w_rev.writerow(header)

            try:
                for item in rows:
                    csv_row = [
                        item.original_path or item.canonical_path,
                        item.category or "",
                        f"{item.confidence:.4f}" if item.confidence is not None else "0.0000",
                        item.tier or "",
                        item.decision_reason or "",
                        "", "0.0000", "0", "0.0000", "", "0.0000", "", "",
                        f"{item.uncertainty:.4f}" if item.uncertainty is not None else "0.0000",
                        item.model_version or "",
                        item.destination_path or "",
                        item.updated_at,
                    ]
                    w_out.writerow(csv_row)
                    written_output += 1
                    if w_rev and item.status == LifecycleStatus.REVIEW_PENDING.value:
                        w_rev.writerow(csv_row)
            finally:
                f_out.flush()
                os.fsync(f_out.fileno())
                if f_rev:
                    f_rev.flush()
                    os.fsync(f_rev.fileno())
                    f_rev.close()

        os.replace(tmp_output, output_csv)
        if tmp_review and review_csv:
            os.replace(tmp_review, review_csv)

        return written_output

    def get_measured_seconds_per_image(self) -> Optional[float]:
        """
        Computes actual average processing time per image (seconds/image) based on
        completed items and their transition timestamps in SQLite.
        Returns None if no historical data is available.
        """
        try:
            with self._get_connection() as conn:
                cur = conn.execute("""
                    SELECT
                        count(*) as count,
                        (julianday(max(timestamp)) - julianday(min(timestamp))) * 86400.0 as duration_sec
                    FROM classifier_transitions
                    WHERE to_status = 'COMPLETED'
                    GROUP BY run_id
                    HAVING count >= 4;
                """)
                rows = cur.fetchall()
                if rows:
                    total_dur = sum(r["duration_sec"] for r in rows if r["duration_sec"] and r["duration_sec"] > 0)
                    total_cnt = sum(r["count"] for r in rows if r["count"] and r["count"] > 0)
                    if total_cnt >= 4 and total_dur > 0:
                        avg_sec = total_dur / float(total_cnt)
                        if 0.5 <= avg_sec <= 60.0:
                            return round(avg_sec, 2)

                # Fallback: check run table duration
                cur = conn.execute("""
                    SELECT
                        r.run_id,
                        (julianday(COALESCE(r.ended_at, r.heartbeat_at)) - julianday(r.started_at)) * 86400.0 as run_dur,
                        count(i.item_id) as completed_items
                    FROM classifier_runs r
                    JOIN classifier_items i ON i.run_id = r.run_id AND i.status = 'COMPLETED' AND (i.tier IS NULL OR i.tier != 'EXIF_CAMERA')
                    GROUP BY r.run_id
                    HAVING completed_items >= 4;
                """)
                run_rows = cur.fetchall()
                if run_rows:
                    total_dur = sum(r["run_dur"] for r in run_rows if r["run_dur"] and r["run_dur"] > 0)
                    total_cnt = sum(r["completed_items"] for r in run_rows if r["completed_items"] and r["completed_items"] > 0)
                    if total_cnt >= 4 and total_dur > 0:
                        avg_sec = total_dur / float(total_cnt)
                        if 0.5 <= avg_sec <= 60.0:
                            return round(avg_sec, 2)
        except Exception:
            pass
        return None

    # -------------------------------------------------------------------------
    # Candidate Discovery & Progress Reconciliation
    # -------------------------------------------------------------------------

    def discover_candidates(
        self,
        source_dir: Path,
        extra_paths: Optional[List[Path]] = None,
        staging_recovered_paths: Optional[List[Path]] = None,
        resume_enabled: bool = True,
        rescue: bool = False,
        fingerprint_strategy: str = "cheap",
        config_fingerprint: Optional[str] = None,
    ) -> DiscoverySummary:
        """
        Discovers, reconciles, and filters candidate images for classification.
        Scans source_dir (under memories/{YYYY}/) and merges:
        - newly extracted candidates (extra_paths)
        - staging-recovered candidates (staging_recovered_paths)
        - incomplete/resumable database items
        - filesystem-discovered photos under memories/{YYYY}/
        Skips already completed unchanged files and review-pending files when resume_enabled.
        Calculates completion/remaining percentages and CPU-based estimated time remaining.
        """
        resolved_source = source_dir.resolve()
        newly_extracted_set: Set[Path] = set()
        for p in (extra_paths or []):
            try:
                res = p.resolve()
                if res.is_file() and res.suffix.lower() in SUPPORTED_PHOTO_EXTENSIONS:
                    newly_extracted_set.add(res)
            except Exception:
                pass
        newly_extracted_count = len(newly_extracted_set)

        staging_recovery_set: Set[Path] = set()
        for p in (staging_recovered_paths or []):
            try:
                res = p.resolve()
                if res.is_file() and res.suffix.lower() in SUPPORTED_PHOTO_EXTENSIONS:
                    staging_recovery_set.add(res)
            except Exception:
                pass
        staging_recovery_count = len(staging_recovery_set)

        # Query database records once for fast in-memory indexing
        items_by_path: Dict[str, sqlite3.Row] = {}
        items_by_fingerprint: Dict[str, sqlite3.Row] = {}
        db_resumed_set: Set[Path] = set()

        with self._get_connection() as conn:
            cur = conn.execute("""
                SELECT item_id, canonical_path, original_path, staged_path, destination_path,
                       fingerprint, status, config_fingerprint
                FROM classifier_items;
            """)
            for row in cur.fetchall():
                can_p = row["canonical_path"]
                orig_p = row["original_path"]
                stg_p = row["staged_path"]
                dest_p = row["destination_path"]
                fp = row["fingerprint"]
                st = row["status"]

                if can_p:
                    items_by_path[can_p] = row
                if orig_p:
                    items_by_path[orig_p] = row
                if dest_p:
                    items_by_path[dest_p] = row
                if fp:
                    items_by_fingerprint[fp] = row

                # Check if item is incomplete/resumable in database
                if st in RESUMABLE_STATUSES:
                    for check_str in (orig_p, can_p, stg_p):
                        if check_str:
                            try:
                                candidate_p = Path(check_str).resolve()
                                if candidate_p.is_file() and candidate_p.suffix.lower() in SUPPORTED_PHOTO_EXTENSIONS:
                                    db_resumed_set.add(candidate_p)
                                    break
                            except Exception:
                                pass

        database_resumed_count = len(db_resumed_set)

        # Filesystem scan under source_dir
        fs_discovered_set: Set[Path] = set()
        if resolved_source.is_dir():
            for root, dirs, files in os.walk(resolved_source):
                # Prune transient/non-photo directories
                dirs[:] = [
                    d for d in dirs
                    if not d.startswith(".")
                    and not d.startswith(".staging-")
                    and not d.startswith("$")
                    and d.lower() not in ("$recycle.bin", "system volume information", "recovery", "config.msi", "msocache", "$winreagent", "$sysreset")
                    and d != ".classifier-state"
                    and d.lower() != "videos"
                    and d.lower() != "review"
                    and (rescue or d != "quarantine")
                ]
                for f in files:
                    if f.startswith("media-extraction-report-"):
                        continue
                    p = (Path(root) / f).resolve()
                    if p.suffix.lower() in SUPPORTED_PHOTO_EXTENSIONS:
                        fs_discovered_set.add(p)

        filesystem_discovered_count = len(fs_discovered_set)

        # Merge all discovered paths deterministically
        all_paths: List[Path] = []
        seen: Set[Path] = set()
        for path_group in (extra_paths or [], staging_recovered_paths or [], list(db_resumed_set), list(fs_discovered_set)):
            for p in path_group:
                try:
                    res = p.resolve()
                    if res not in seen and res.is_file() and res.suffix.lower() in SUPPORTED_PHOTO_EXTENSIONS:
                        seen.add(res)
                        all_paths.append(res)
                except Exception:
                    pass

        already_completed_count = 0
        review_pending_count = 0
        final_candidates: List[Path] = []

        for p in all_paths:
            p_str = str(p)
            if not resume_enabled:
                final_candidates.append(p)
                continue

            try:
                fp, sz, mt = compute_file_fingerprint(p, strategy=fingerprint_strategy)
            except Exception:
                continue

            item = items_by_path.get(p_str) or items_by_fingerprint.get(fp)
            if item:
                st = item["status"]
                item_fp = item["fingerprint"]
                item_cfg = item["config_fingerprint"]

                if st == LifecycleStatus.COMPLETED.value:
                    if config_fingerprint is None or item_cfg == config_fingerprint:
                        if item_fp == fp:
                            already_completed_count += 1
                            continue
                        else:
                            # Modified file
                            final_candidates.append(p)
                            continue
                    else:
                        # Invalidation due to config/model change
                        final_candidates.append(p)
                        continue
                elif st == LifecycleStatus.REVIEW_PENDING.value:
                    if item_fp == fp:
                        review_pending_count += 1
                        continue
                    else:
                        final_candidates.append(p)
                        continue

            final_candidates.append(p)

        total_photos = already_completed_count + len(final_candidates) + review_pending_count
        if total_photos > 0:
            completed_pct = round((already_completed_count * 100.0) / total_photos, 1)
            remaining_pct = round((len(final_candidates) * 100.0) / total_photos, 1)
        else:
            completed_pct = 100.0 if already_completed_count > 0 else 0.0
            remaining_pct = 0.0

        cpu_cores = os.cpu_count() or 4
        measured_sec = self.get_measured_seconds_per_image()
        eta_sec, eta_formatted, sec_per_img, rate_source = estimate_processing_time(
            len(final_candidates), cpu_cores=cpu_cores, measured_seconds_per_image=measured_sec
        )

        return DiscoverySummary(
            newly_extracted=newly_extracted_count,
            filesystem_discovered=filesystem_discovered_count,
            database_resumed=database_resumed_count,
            staging_recovery=staging_recovery_count,
            already_completed=already_completed_count,
            review_pending=review_pending_count,
            final_candidates_count=len(final_candidates),
            completed_percentage=completed_pct,
            remaining_percentage=remaining_pct,
            cpu_cores=cpu_cores,
            estimated_seconds_remaining=eta_sec,
            estimated_time_formatted=eta_formatted,
            candidate_paths=[str(p) for p in final_candidates],
            seconds_per_image=sec_per_img,
            rate_source=rate_source,
        )

    def record_certified_items(
        self,
        paths: Sequence[Union[str, Path]],
        decision_reason: str = "Certified camera photo (Java triage)",
        config_fingerprint: Optional[str] = None,
        strategy: str = "cheap"
    ) -> int:
        """
        Records camera photos certified by Java triage directly as COMPLETED in SQLite,
        allowing subsequent runs to skip them cleanly without repeating triage.
        """
        if not paths:
            return 0
        now_iso = current_iso_timestamp()
        recorded = 0
        with self._get_connection() as conn:
            conn.execute("BEGIN IMMEDIATE;")
            for p in paths:
                try:
                    resolved = Path(p).resolve()
                    if not resolved.is_file():
                        continue
                    fp, sz, mt = compute_file_fingerprint(resolved, strategy=strategy)
                    item_id = compute_item_id(str(resolved))
                    conn.execute("""
                        INSERT INTO classifier_items (
                            item_id, canonical_path, original_path, file_size, mtime_ns,
                            fingerprint, status, category, confidence, tier,
                            decision_reason, uncertainty, intended_destination, destination_path,
                            config_fingerprint, run_id, attempt_count, created_at, updated_at
                        ) VALUES (
                            ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, 1, ?, ?
                        )
                        ON CONFLICT(item_id) DO UPDATE SET
                            fingerprint = excluded.fingerprint,
                            status = excluded.status,
                            category = excluded.category,
                            confidence = excluded.confidence,
                            tier = excluded.tier,
                            decision_reason = excluded.decision_reason,
                            destination_path = excluded.destination_path,
                            config_fingerprint = excluded.config_fingerprint,
                            updated_at = excluded.updated_at;
                    """, (
                        item_id, str(resolved), str(resolved), sz, mt,
                        fp, LifecycleStatus.COMPLETED.value, "PHOTO", 1.0, "EXIF_CAMERA",
                        decision_reason, 0.0, str(resolved), str(resolved),
                        config_fingerprint, self.run_id, now_iso, now_iso
                    ))
                    recorded += 1
                except Exception:
                    continue
            conn.execute("COMMIT;")
        return recorded


def main():
    parser = argparse.ArgumentParser(description="Media Extractor state store & candidate discovery CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # Subcommand: discover
    p_disc = subparsers.add_parser("discover", help="Discover and reconcile pending classification candidates")
    p_disc.add_argument("--source-dir", type=str, required=True, help="Base memories directory")
    p_disc.add_argument("--state-db", type=str, default=None, help="Path to SQLite state database")
    p_disc.add_argument("--extra-paths-file", type=str, default=None, help="File containing newly extracted paths (one per line)")
    p_disc.add_argument("--staging-recovered-file", type=str, default=None, help="File containing staging-recovered paths (one per line)")
    p_disc.add_argument("--output-json", type=str, default=None, help="Path to write JSON discovery summary")
    p_disc.add_argument("--output-paths-file", type=str, default=None, help="Path to write candidate paths (one per line)")
    p_disc.add_argument("--fingerprint-strategy", type=str, default="cheap", choices=["cheap", "full-sha256"])
    p_disc.add_argument("--config-fingerprint", type=str, default=None, help="Run configuration fingerprint")
    p_disc.add_argument("--no-resume", action="store_true", help="Disable resume and return all discovered candidates")
    p_disc.add_argument("--rescue", action="store_true", help="Enable rescue mode (includes quarantine directory)")

    # Subcommand: record-certified
    p_cert = subparsers.add_parser("record-certified", help="Record Java-certified camera photos as COMPLETED")
    p_cert.add_argument("--state-db", type=str, default=None, help="Path to SQLite state database")
    p_cert.add_argument("--paths-file", type=str, required=True, help="File containing certified photo paths")
    p_cert.add_argument("--config-fingerprint", type=str, default=None)
    p_cert.add_argument("--fingerprint-strategy", type=str, default="cheap")

    args = parser.parse_args()

    db_path = Path(args.state_db) if args.state_db else None
    if db_path is None and hasattr(args, "source_dir") and args.source_dir:
        db_path = (Path(args.source_dir) / ".classifier-state" / "classification.sqlite3").resolve()
    state_store = ClassifierStateStore(db_path=db_path)

    if args.command == "discover":
        extra_paths: List[Path] = []
        if args.extra_paths_file and os.path.exists(args.extra_paths_file):
            with open(args.extra_paths_file, "r", encoding="utf-8") as f:
                extra_paths = [Path(line.strip()) for line in f if line.strip()]

        staging_paths: List[Path] = []
        if args.staging_recovered_file and os.path.exists(args.staging_recovered_file):
            with open(args.staging_recovered_file, "r", encoding="utf-8") as f:
                staging_paths = [Path(line.strip()) for line in f if line.strip()]

        summary = state_store.discover_candidates(
            source_dir=Path(args.source_dir),
            extra_paths=extra_paths,
            staging_recovered_paths=staging_paths,
            resume_enabled=not args.no_resume,
            rescue=args.rescue,
            fingerprint_strategy=args.fingerprint_strategy,
            config_fingerprint=args.config_fingerprint
        )

        out_data = summary.to_dict()
        if args.output_json:
            out_p = Path(args.output_json)
            out_p.parent.mkdir(parents=True, exist_ok=True)
            with open(out_p, "w", encoding="utf-8") as f:
                json.dump(out_data, f, indent=2)

        if args.output_paths_file:
            out_paths_p = Path(args.output_paths_file)
            out_paths_p.parent.mkdir(parents=True, exist_ok=True)
            with open(out_paths_p, "w", encoding="utf-8") as f:
                for cp in summary.candidate_paths:
                    f.write(f"{cp}\n")


        total_considered = summary.already_completed + summary.final_candidates_count + summary.review_pending
        print(f"[INFO] Discovered candidates breakdown: newly_extracted={summary.newly_extracted}, filesystem={summary.filesystem_discovered}, db_resumed={summary.database_resumed}, staging_recovered={summary.staging_recovery}")
        print(f"[INFO] Classification resume progress: {summary.completed_percentage:.1f}% previously completed ({summary.already_completed}/{total_considered} photos), {summary.remaining_percentage:.1f}% remaining ({summary.final_candidates_count}/{total_considered} photos) | Estimated remaining time: ~{summary.estimated_time_formatted} (~{summary.seconds_per_image:.1f}s/img via {summary.rate_source} on {summary.cpu_cores} CPUs)")
        if not args.output_json:
            print(json.dumps(out_data))

    elif args.command == "record-certified":
        paths: List[Path] = []
        if os.path.exists(args.paths_file):
            with open(args.paths_file, "r", encoding="utf-8") as f:
                paths = [Path(line.strip()) for line in f if line.strip()]
        rec = state_store.record_certified_items(
            paths=paths,
            config_fingerprint=args.config_fingerprint,
            strategy=args.fingerprint_strategy
        )
        print(f"[INFO] Recorded {rec} certified photos in state store.")


if __name__ == "__main__":
    main()
