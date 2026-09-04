package com.example.mediaextractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ProgressReporter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProgressReporter.class);
    private final ExtractionReport report;
    private final long intervalMillis;
    private volatile boolean running;
    private Thread thread;

    ProgressReporter(ExtractionReport report, long intervalMillis) {
        this.report = report;
        this.intervalMillis = intervalMillis;
    }

    void start() {
        running = true;
        thread = Thread.startVirtualThread(() -> {
            while (running) {
                log.info("Progress: scanned={}, completed={}, in-flight={}, extracted={}, duplicates={}, corrupted={}, failed={}, rate={}/s",
                        report.scanned(), report.completed(), report.inFlight(), report.extracted(),
                        report.duplicates(), report.corrupted(), report.failed(), String.format("%.2f", report.filesPerSecond()));
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}