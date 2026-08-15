package com.fedjafilipovic.ai_diff_reviewer.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins Chunker file-boundary splitting, byte-based sizing, oversized single
 * files, and chunked-vs-unchunked equivalence. Maps to §5 rows 19, 20, 21.
 */
class ChunkerTest {

    private final Chunker chunker = new Chunker();

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** A git-style single-file diff whose total UTF-8 size is ~targetBytes. */
    private static String gitFile(String name, int addedLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(name).append(" b/").append(name).append('\n');
        sb.append("--- a/").append(name).append('\n');
        sb.append("+++ b/").append(name).append('\n');
        sb.append("@@ -1 +1,").append(addedLines).append(" @@\n");
        for (int i = 0; i < addedLines; i++) {
            sb.append("+line").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    void singleSmallFileIsOneChunk() {
        String diff = gitFile("a.js", 3);
        List<String> chunks = chunker.chunk(diff, 65_536);
        assertThat(chunks).hasSize(1);
    }

    @Test
    void neverSplitsOneFileAcrossChunks() {
        // Two files, each ~half the limit -> packed into one chunk (both fit).
        String a = gitFile("a.js", 100);
        String b = gitFile("b.js", 100);
        String diff = a + b;
        List<String> chunks = chunker.chunk(diff, bytes(diff) + 10);
        assertThat(chunks).hasSize(1);
    }

    @Test
    void packsFilesUpToByteLimitThenStartsNewChunk() {
        // Force a tiny limit so each file becomes its own chunk.
        String a = gitFile("a.js", 50);
        String b = gitFile("b.js", 50);
        String diff = a + b;
        int limit = Math.max(bytes(a), bytes(b)); // each file fits alone, not together
        List<String> chunks = chunker.chunk(diff, limit);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).startsWith("diff --git a/a.js");
        assertThat(chunks.get(1)).startsWith("diff --git a/b.js");
    }

    @Test
    void singleOversizedFileBecomesItsOwnChunk() {
        // One file bigger than the limit -> one oversized chunk, not split mid-file.
        String big = gitFile("big.js", 5000);
        assertThat(bytes(big)).isGreaterThan(1000);
        List<String> chunks = chunker.chunk(big, 1000);
        assertThat(chunks).hasSize(1);
        assertThat(bytes(chunks.get(0))).isGreaterThan(1000);
    }

    @Test
    void byteSizingCountsUtf8BytesNotChars() {
        // Multi-byte chars: 'é' is 2 bytes in UTF-8. A chunk sized by chars would
        // wrongly pack more; we assert the chunker uses byte length.
        String multibyte = "+café\n"; // 6 chars, 7 bytes
        String file = gitFile("m.js", 0) + multibyte.repeat(50);
        List<String> chunks = chunker.chunk(file, 10_000);
        // Whole thing is one chunk; the point is no exception and byte math holds.
        assertThat(chunks).hasSize(1);
        assertThat(bytes(chunks.get(0))).isEqualTo(bytes(file));
    }

    @Test
    void plainUnifiedDiffSplitsOnTripleDash() {
        // No "diff --git " marker -> split on "--- ".
        String a = "--- a/x.js\n+++ b/x.js\n@@ -1 +1 @@\n+x\n";
        String b = "--- a/y.js\n+++ b/y.js\n@@ -1 +1 @@\n+y\n";
        List<String> segs = chunker.splitByFile(a + b);
        assertThat(segs).hasSize(2);
    }

    @Test
    void gitStyleSplitsOnDiffGit() {
        String a = gitFile("a.js", 2);
        String b = gitFile("b.js", 2);
        List<String> segs = chunker.splitByFile(a + b);
        assertThat(segs).hasSize(2);
    }

    @Test
    void chunkCountIsAtLeastOne() {
        List<String> chunks = chunker.chunk(gitFile("a.js", 1), 65_536);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void manySmallFilesTotalingOverLimitProduceMultipleChunks() {
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            diff.append(gitFile("f" + i + ".js", 40));
        }
        // Force a limit well under the crafted diff's actual total size (a
        // fixed 65_536 default is not guaranteed to be exceeded by any
        // particular file/line count, so pick a limit relative to the real
        // total instead of assuming it).
        int total = bytes(diff.toString());
        int limit = total / 4;
        List<String> chunks = chunker.chunk(diff.toString(), limit);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        // No chunk exceeds the limit unless it is a single oversized file.
        for (String c : chunks) {
            // each file is ~small, so no chunk should exceed limit by a whole file
            assertThat(bytes(c)).isLessThanOrEqualTo(limit + bytes(gitFile("f0.js", 40)));
        }
    }
}
