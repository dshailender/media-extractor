package com.example.mediaextractor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OutputDirectoryResolutionTest {

    @Test
    void testDefaultOutputDirectoryWhenUnspecified() {
        Path defaultOutput = MediaExtractorApplication.resolveOutputDirectory(null, null);
        Path expected = Path.of(System.getProperty("user.home")).resolve("archive").resolve("memories").toAbsolutePath().normalize();
        assertEquals(expected, defaultOutput);

        Path blankOutput = MediaExtractorApplication.resolveOutputDirectory("   ", "");
        assertEquals(expected, blankOutput);
    }

    @Test
    void testOutputDirectoryFromConfiguredProperty() {
        Path configured = MediaExtractorApplication.resolveOutputDirectory(null, "/custom/property/path");
        assertEquals(Path.of("/custom/property/path").toAbsolutePath().normalize(), configured);
    }

    @Test
    void testCliArgumentOverridesConfiguredProperty() {
        Path resolved = MediaExtractorApplication.resolveOutputDirectory("/cli/specified/path", "/custom/property/path");
        assertEquals(Path.of("/cli/specified/path").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void testTildeExpansion() {
        String userHome = System.getProperty("user.home");

        Path tildeOnly = MediaExtractorApplication.expandUserHome("~");
        assertEquals(Path.of(userHome), tildeOnly);

        Path tildePath = MediaExtractorApplication.expandUserHome("~/my-custom-memories");
        assertEquals(Path.of(userHome).resolve("my-custom-memories"), tildePath);

        Path resolved = MediaExtractorApplication.resolveOutputDirectory("~/archive/memories", null);
        assertEquals(Path.of(userHome).resolve("archive/memories").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void testPositionalArgumentParsing() {
        MediaExtractorApplication.ParsedArguments args1 = MediaExtractorApplication.parseArguments("/source/backup");
        assertEquals("/source/backup", args1.sourceArgument());
        assertNull(args1.outputArgument());

        MediaExtractorApplication.ParsedArguments args2 = MediaExtractorApplication.parseArguments("/source/backup", "/custom/output");
        assertEquals("/source/backup", args2.sourceArgument());
        assertEquals("/custom/output", args2.outputArgument());
    }

    @Test
    void testOutputFlagsParsing() {
        MediaExtractorApplication.ParsedArguments argsEqual = MediaExtractorApplication.parseArguments("/source", "--output=/dir/out");
        assertEquals("/source", argsEqual.sourceArgument());
        assertEquals("/dir/out", argsEqual.outputArgument());

        MediaExtractorApplication.ParsedArguments argsSpace = MediaExtractorApplication.parseArguments("/source", "--output", "/dir/out");
        assertEquals("/source", argsSpace.sourceArgument());
        assertEquals("/dir/out", argsSpace.outputArgument());

        MediaExtractorApplication.ParsedArguments argsDirEqual = MediaExtractorApplication.parseArguments("/source", "--output-dir=/dir/out2");
        assertEquals("/dir/out2", argsDirEqual.outputArgument());

        MediaExtractorApplication.ParsedArguments argsDirSpace = MediaExtractorApplication.parseArguments("/source", "--output-dir", "/dir/out2");
        assertEquals("/dir/out2", argsDirSpace.outputArgument());

        MediaExtractorApplication.ParsedArguments argsShortEqual = MediaExtractorApplication.parseArguments("/source", "-o=/dir/short");
        assertEquals("/dir/short", argsShortEqual.outputArgument());

        MediaExtractorApplication.ParsedArguments argsShortSpace = MediaExtractorApplication.parseArguments("/source", "-o", "/dir/short");
        assertEquals("/dir/short", argsShortSpace.outputArgument());
    }

    @Test
    void testFlagOverridesPositionalOutput() {
        MediaExtractorApplication.ParsedArguments parsed = MediaExtractorApplication.parseArguments(
                "/source", "/positional/output", "--output=/flag/output"
        );
        assertEquals("/source", parsed.sourceArgument());
        assertEquals("/flag/output", parsed.outputArgument());
    }

    @Test
    void testOutputFlagBeforeSourceArgument() {
        MediaExtractorApplication.ParsedArguments parsed = MediaExtractorApplication.parseArguments(
                "--output", "/custom/output", "/source/backup"
        );
        assertEquals("/source/backup", parsed.sourceArgument());
        assertEquals("/custom/output", parsed.outputArgument());
    }

    @Test
    void testResumeAndSanitizeModesWithOutput() {
        MediaExtractorApplication.ParsedArguments resumeArgs = MediaExtractorApplication.parseArguments(
                "--resume", "--output=/resume/memories"
        );
        assertNull(resumeArgs.sourceArgument());
        assertEquals("/resume/memories", resumeArgs.outputArgument());
        assertTrue(resumeArgs.classifyOnly());
        assertTrue(Boolean.TRUE.equals(resumeArgs.classifierEnabledOverride()));

        MediaExtractorApplication.ParsedArguments sanitizeArgs = MediaExtractorApplication.parseArguments(
                "--sanitize", "-o", "/sanitize/memories"
        );
        assertNull(sanitizeArgs.sourceArgument());
        assertEquals("/sanitize/memories", sanitizeArgs.outputArgument());
        assertTrue(sanitizeArgs.sanitizeMode());
    }

    @Test
    void testClassifyOnlyFlagDefaultOutput() {
        MediaExtractorApplication.ParsedArguments parsed = MediaExtractorApplication.parseArguments("--classify");
        assertTrue(parsed.classifyOnly());
        assertTrue(Boolean.TRUE.equals(parsed.classifierEnabledOverride()));
        assertNull(parsed.sourceArgument());
        assertNull(parsed.outputArgument());

        Path resolved = MediaExtractorApplication.resolveOutputDirectory(parsed.outputArgument(), null);
        assertEquals(Path.of(System.getProperty("user.home")).resolve("archive").resolve("memories").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void testClassifyOnlyWithPositionalOutput() {
        // Flag first, then directory
        MediaExtractorApplication.ParsedArguments parsed1 = MediaExtractorApplication.parseArguments(
                "--classify", "/custom/memories"
        );
        assertTrue(parsed1.classifyOnly());
        assertNull(parsed1.sourceArgument());
        assertEquals("/custom/memories", parsed1.outputArgument());

        // Directory first, then flag
        MediaExtractorApplication.ParsedArguments parsed2 = MediaExtractorApplication.parseArguments(
                "/custom/memories", "--classify"
        );
        assertTrue(parsed2.classifyOnly());
        assertNull(parsed2.sourceArgument());
        assertEquals("/custom/memories", parsed2.outputArgument());
    }

    @Test
    void testClassifyOnlyWithOutputFlags() {
        MediaExtractorApplication.ParsedArguments parsedEqual = MediaExtractorApplication.parseArguments(
                "--classify", "--output=/custom/memories"
        );
        assertTrue(parsedEqual.classifyOnly());
        assertNull(parsedEqual.sourceArgument());
        assertEquals("/custom/memories", parsedEqual.outputArgument());

        MediaExtractorApplication.ParsedArguments parsedShort = MediaExtractorApplication.parseArguments(
                "-o", "~/classified_memories", "--classify"
        );
        assertTrue(parsedShort.classifyOnly());
        assertNull(parsedShort.sourceArgument());
        assertEquals("~/classified_memories", parsedShort.outputArgument());

        Path resolved = MediaExtractorApplication.resolveOutputDirectory(parsedShort.outputArgument(), null);
        assertEquals(Path.of(System.getProperty("user.home")).resolve("classified_memories").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void testClassifyOnlyOverridesPositionalSource() {
        MediaExtractorApplication.ParsedArguments parsed = MediaExtractorApplication.parseArguments(
                "/some/source", "/custom/output", "--classify"
        );
        assertTrue(parsed.classifyOnly());
        assertNull(parsed.sourceArgument());
        assertEquals("/custom/output", parsed.outputArgument());
    }

    @Test
    void testClassifyOnlyAliases() {
        MediaExtractorApplication.ParsedArguments parsedOnly = MediaExtractorApplication.parseArguments(
                "--classify-only", "/my/output"
        );
        assertTrue(parsedOnly.classifyOnly());
        assertNull(parsedOnly.sourceArgument());
        assertEquals("/my/output", parsedOnly.outputArgument());

        MediaExtractorApplication.ParsedArguments parsedResume = MediaExtractorApplication.parseArguments(
                "--resume", "/my/output"
        );
        assertTrue(parsedResume.classifyOnly());
        assertNull(parsedResume.sourceArgument());
        assertEquals("/my/output", parsedResume.outputArgument());
    }
}

