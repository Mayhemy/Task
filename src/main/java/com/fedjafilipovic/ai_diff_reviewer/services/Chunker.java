package com.fedjafilipovic.ai_diff_reviewer.services;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a diff into chunks of at most {@code maxBytes} UTF-8 bytes, only on
 * file boundaries. A single file whose diff exceeds maxBytes becomes its own
 * (oversized) chunk — explicitly allowed by the spec. Byte counting is always
 * UTF-8 bytes, never String.length().
 */
@Component
public class Chunker {

    public List<String> chunk(String diffText, int maxBytes) {
        return pack(splitByFile(diffText), maxBytes);
    }

    /** Visible for testing: split into per-file segments, markers kept with their segment. */
    List<String> splitByFile(String diffText) {
        boolean gitStyle = isGitStyle(diffText);
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String[] lines = diffText.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isFileBoundary(lines, i, gitStyle) && cur.length() > 0) {
                segments.add(cur.toString());
                cur = new StringBuilder();
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) {
            segments.add(cur.toString());
        }
        // split("\n", -1) always yields one more element than there are real
        // newlines (a trailing "" when diffText ends with '\n', or simply the
        // final line otherwise) and every element gets '\n' appended
        // unconditionally — so the last segment always carries exactly one
        // spurious trailing '\n' not present in diffText. Earlier segments are
        // byte-exact (each ends where a real '\n' actually was, since more
        // content followed). Strip that one extra character so the full
        // concatenation of all segments reconstructs diffText exactly.
        int lastIdx = segments.size() - 1;
        if (lastIdx >= 0) {
            String last = segments.get(lastIdx);
            if (last.endsWith("\n")) {
                segments.set(lastIdx, last.substring(0, last.length() - 1));
            }
        }
        return segments;
    }

    /**
     * Anchored to the start of a LINE, not a bare {@code contains}. The
     * announcement is only an announcement in column zero; the same text sitting
     * mid-line is ordinary data — most plausibly a plain {@code diff -u} whose
     * hunk header carries a section heading after the {@code @@}, or a diff of a
     * file that discusses diffs. A bare contains would flip such a payload into
     * git mode, where nothing starts with "diff --git " and so NO boundary is
     * ever found: the whole thing collapses into one oversized chunk that
     * reports {@code usage.chunks: 1} for a diff well over the declared 64 KiB.
     * Findings would still be right (never splitting cannot lose anything), but
     * the declared chunking behaviour would not be.
     */
    private static boolean isGitStyle(String diffText) {
        return diffText.startsWith("diff --git ") || diffText.contains("\ndiff --git ");
    }

    /**
     * A git diff announces each file with "diff --git ", which no content line
     * can imitate — a content line always carries a +/-/space marker first.
     *
     * A plain `diff -u` has no such announcement, so the only boundary is the
     * "--- " old-file marker, and that one CAN be imitated: removing a line
     * whose text starts with "-- " emits the raw line "--- ...". Splitting
     * there would cut a file's hunk in half, and the second half would arrive
     * at the parser with no +++ header above it — every finding in it silently
     * lost, which is precisely the "no losses" property the chunking probes
     * check. Requiring the +++ partner on the next line removes the ambiguity:
     * a real unified diff always writes the two markers adjacently.
     */
    private static boolean isFileBoundary(String[] lines, int i, boolean gitStyle) {
        String line = lines[i];
        if (gitStyle) {
            return line.startsWith("diff --git ");
        }
        return line.startsWith("--- ") && i + 1 < lines.length && lines[i + 1].startsWith("+++ ");
    }

    private List<String> pack(List<String> fileSegments, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curBytes = 0;
        for (String seg : fileSegments) {
            int segBytes = seg.getBytes(StandardCharsets.UTF_8).length;
            if (curBytes > 0 && curBytes + segBytes > maxBytes) {
                chunks.add(cur.toString());
                cur = new StringBuilder();
                curBytes = 0;
            }
            cur.append(seg);
            curBytes += segBytes;
            if (curBytes > maxBytes) {
                // This single file alone is oversized — it becomes its own chunk.
                chunks.add(cur.toString());
                cur = new StringBuilder();
                curBytes = 0;
            }
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString());
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }
}
