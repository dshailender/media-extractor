package com.example.mediaextractor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class ExtractionReport {
    private final Instant startedAt = Instant.now();
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong extracted = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong corrupted = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong peakInFlight = new AtomicLong();
    private final AtomicLong quarantined = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> byType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, AtomicLong>> byTypeAndYear = new ConcurrentHashMap<>();
    private final List<Failure> failures = Collections.synchronizedList(new ArrayList<>());
    private volatile Instant finishedAt;

    void recordScanned() { scanned.incrementAndGet(); }
    void recordQueued() { queued.incrementAndGet(); }
    void started() {
        long current = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(current, Math::max);
    }
    void recordCompleted() { inFlight.decrementAndGet(); completed.incrementAndGet(); }

    void extracted(String type, int year, long bytes) {
        extracted.incrementAndGet();
        bytesWritten.addAndGet(bytes);
        byType.computeIfAbsent(type, ignored -> new AtomicLong()).incrementAndGet();
        byTypeAndYear.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(year, ignored -> new AtomicLong()).incrementAndGet();
    }
    void duplicate(String source) { duplicates.incrementAndGet(); addFailure(new Failure(source, "duplicate", "content already extracted")); }
    void corrupted(String source, String reason) { corrupted.incrementAndGet(); addFailure(new Failure(source, "corruption", reason)); }
    void quarantined(String source, String reason) { quarantined.incrementAndGet(); addFailure(new Failure(source, "quarantine", reason)); }
    void failed(String source, String stage, String reason) { failed.incrementAndGet(); addFailure(new Failure(source, stage, reason)); }
    void finish() { finishedAt = Instant.now(); }

    private void addFailure(Failure failure) {
        if (failures.size() < 10000) {
            failures.add(failure);
        }
    }

    Instant startedAt() { return startedAt; }
    Instant finishedAt() { return finishedAt; }
    long scanned() { return scanned.get(); }
    long queued() { return queued.get(); }
    long completed() { return completed.get(); }
    long inFlight() { return inFlight.get(); }
    long extracted() { return extracted.get(); }
    long duplicates() { return duplicates.get(); }
    long corrupted() { return corrupted.get(); }
    long quarantined() { return quarantined.get(); }
    long failed() { return failed.get(); }
    long bytesWritten() { return bytesWritten.get(); }
    long peakInFlight() { return peakInFlight.get(); }
    Map<String, AtomicLong> byType() { return Collections.unmodifiableMap(byType); }
    Map<String, ConcurrentHashMap<Integer, AtomicLong>> byTypeAndYear() { return Collections.unmodifiableMap(byTypeAndYear); }
    List<Failure> failures() { synchronized (failures) { return List.copyOf(failures); } }
    long durationMillis() { return Duration.between(startedAt, finishedAt == null ? Instant.now() : finishedAt).toMillis(); }
    double filesPerSecond() { return durationMillis() == 0 ? extracted() : extracted() * 1000.0 / durationMillis(); }

    record Failure(String source, String stage, String reason) { }
}