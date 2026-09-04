package com.example.mediaextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class ExtractionReportWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    ReportPaths write(ExtractionReport report, Path directory, String source, String output) throws IOException {
        Files.createDirectories(directory);
        String baseName = "media-extraction-report-" + LocalDateTime.now().format(FILE_TIME);
        Path json = directory.resolve(baseName + ".json");
        Path html = directory.resolve(baseName + ".html");
        writeAtomically(json, toJson(report, source, output));
        writeAtomically(html, toHtml(report, source, output));
        return new ReportPaths(json, html);
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String toJson(ExtractionReport report, String source, String output) {
        StringBuilder json = new StringBuilder("{\n");
        field(json, "source", source, true);
        field(json, "output", output, true);
        field(json, "startedAt", report.startedAt().toString(), true);
        field(json, "finishedAt", report.finishedAt() == null ? "" : report.finishedAt().toString(), true);
        number(json, "durationMillis", report.durationMillis(), true);
        number(json, "filesPerSecond", report.filesPerSecond(), true);
        number(json, "scanned", report.scanned(), true);
        number(json, "queued", report.queued(), true);
        number(json, "completed", report.completed(), true);
        number(json, "extracted", report.extracted(), true);
        number(json, "duplicates", report.duplicates(), true);
        number(json, "corrupted", report.corrupted(), true);
        number(json, "quarantined", report.quarantined(), true);
        number(json, "failed", report.failed(), true);
        number(json, "bytesWritten", report.bytesWritten(), true);
        number(json, "peakInFlight", report.peakInFlight(), true);
        json.append("  \"byType\": ").append(mapToJson(report.byType())).append(",\n");
        json.append("  \"byTypeAndYear\": ").append(yearMapToJson(report)).append(",\n");
        json.append("  \"failures\": [");
        var failures = report.failures();
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) json.append(',');
            var failure = failures.get(i);
                json.append("{\"source\":").append(jsonQuote(failure.source())).append(",\"stage\":")
                    .append(jsonQuote(failure.stage())).append(",\"reason\":").append(jsonQuote(failure.reason())).append('}');
        }
        return json.append("]\n}\n").toString();
    }

    private String toHtml(ExtractionReport report, String source, String output) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Media extraction report</title></head><body>");
        html.append("<h1>Media extraction report</h1><p>Source: <code>").append(escape(source)).append("</code><br>Output: <code>").append(escape(output)).append("</code></p>");
        html.append("<table><tr><th>Metric</th><th>Value</th></tr>");
        metric(html, "Scanned", report.scanned()); metric(html, "Extracted", report.extracted()); metric(html, "Duplicates", report.duplicates());
        metric(html, "Corrupted", report.corrupted()); metric(html, "Quarantined", report.quarantined()); metric(html, "Failed", report.failed()); metric(html, "Duration (ms)", report.durationMillis());
        metric(html, "Files/second", report.filesPerSecond()); metric(html, "Bytes written", report.bytesWritten()); metric(html, "Peak in-flight", report.peakInFlight());
        html.append("</table><h2>Failures and skips</h2><ul>");
        for (var failure : report.failures()) html.append("<li><strong>").append(escape(failure.stage())).append("</strong> ").append(escape(failure.source())).append(": ").append(escape(failure.reason())).append("</li>");
        return html.append("</ul></body></html>").toString();
    }

    private void metric(StringBuilder html, String name, Object value) { html.append("<tr><td>").append(escape(name)).append("</td><td>").append(escape(String.valueOf(value))).append("</td></tr>"); }
    private void field(StringBuilder json, String name, String value, boolean comma) { json.append("  \"").append(name).append("\":").append(jsonQuote(value)).append(comma ? ",\n" : "\n"); }
    private void number(StringBuilder json, String name, double value, boolean comma) { json.append("  \"").append(name).append("\":").append(value).append(comma ? ",\n" : "\n"); }
    private String mapToJson(java.util.Map<String, java.util.concurrent.atomic.AtomicLong> map) { StringBuilder result = new StringBuilder("{"); int i = 0; for (var entry : map.entrySet()) { if (i++ > 0) result.append(','); result.append(jsonQuote(entry.getKey())).append(':').append(entry.getValue().get()); } return result.append('}').toString(); }
    private String yearMapToJson(ExtractionReport report) { StringBuilder result = new StringBuilder("{"); int i = 0; for (var type : report.byTypeAndYear().entrySet()) { if (i++ > 0) result.append(','); result.append(jsonQuote(type.getKey())).append(':').append(yearCountsToJson(type.getValue())); } return result.append('}').toString(); }
    private String yearCountsToJson(java.util.Map<Integer, java.util.concurrent.atomic.AtomicLong> map) { StringBuilder result = new StringBuilder("{"); int i = 0; for (var entry : map.entrySet()) { if (i++ > 0) result.append(','); result.append(jsonQuote(String.valueOf(entry.getKey()))).append(':').append(entry.getValue().get()); } return result.append('}').toString(); }
    private String jsonQuote(String value) { return "\"" + jsonEscape(value) + "\""; }
    private String jsonEscape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"); }
    private String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }

    record ReportPaths(Path json, Path html) { }
}