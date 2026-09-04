package com.example.mediaextractor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionReportWriterTest {
    @Test
    void writesJsonAndEscapedHtmlReports() throws Exception {
        ExtractionReport report = new ExtractionReport();
        report.recordScanned();
        report.recordQueued();
        report.started();
        report.extracted("photo", 2022, 42);
        report.duplicate("source/<duplicate>.jpg");
        report.recordCompleted();
        report.finish();

        Path directory = Files.createTempDirectory("report-test-");
        try {
            ExtractionReportWriter.ReportPaths paths = new ExtractionReportWriter()
                    .write(report, directory, "source/<backup>", "output");
            String json = Files.readString(paths.json(), StandardCharsets.UTF_8);
            String html = Files.readString(paths.html(), StandardCharsets.UTF_8);

            assertTrue(json.contains("\"extracted\":1"));
            assertTrue(json.contains("\"duplicates\":1"));
            assertTrue(json.contains("\"2022\":1"));
            assertTrue(html.contains("source/&lt;backup&gt;"));
            assertTrue(html.contains("source/&lt;duplicate&gt;.jpg"));
        } finally {
            Files.walk(directory).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }
}