package com.fedjafilipovic.ai_diff_reviewer.services;

import com.fedjafilipovic.ai_diff_reviewer.exceptions.InvalidDiffException;
import com.fedjafilipovic.ai_diff_reviewer.models.DiffLine;
import com.fedjafilipovic.ai_diff_reviewer.models.LineType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified diffs into typed hunk lines.
 *
 * Line-numbering invariant: the new-file counter derives from each hunk's own
 * @@ header, never from a running total across the diff. Context (' ') and
 * added ('+') lines advance it; removed ('-') lines do not. This is what makes
 * file-boundary chunking exact — splitting between files can never shift a
 * line number.
 *
 * Header disambiguation: "+++ " and "--- " are only file markers in the right
 * position, because inside a hunk they are also perfectly ordinary content.
 * Adding a line that itself begins "++ " produces the raw line "+++ ...", and
 * removing one beginning "-- " produces "--- ...". Two positional facts settle
 * it, and both come straight from the unified-diff format: the markers always
 * appear as an adjacent --- / +++ pair, and a marker pair never appears in the
 * middle of a hunk body. See {@code isInHunk}.
 */
@Component
public class DiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /**
     * @return all hunk lines (added, context, removed) in order
     * @throws InvalidDiffException if the text contains no hunk headers
     */
    public List<DiffLine> parse(String diffText) {
        return parse(diffText, true);
    }

    /**
     * @param requireHunk whether a diff with no @@ header at all is an error.
     *        True for the whole submitted diff (that is the 422 gate: text with
     *        no hunk anywhere is not a diff). False for an individual chunk —
     *        a chunk may legitimately contain only hunkless file entries (a
     *        pure rename, a mode change, "Binary files ... differ"), and
     *        failing the job over one would be wrong. See STATUS.md §6.26.
     */
    public List<DiffLine> parse(String diffText, boolean requireHunk) {
        List<DiffLine> out = new ArrayList<>();
        String currentPath = null;
        int newLine = 0;
        boolean sawHunk = false;
        boolean inHunk = false;
        boolean prevWasOldMarker = false;

        // split("\n", -1) yields a trailing "" artifact whenever diffText ends
        // with '\n' (virtually always). Now that a genuinely empty line is
        // meaningful (treated as a blank context line below), that artifact
        // must not be iterated as if it were a real line — strip exactly the
        // one trailing '\n' that produced it first. A text ending in "\n\n"
        // (a real blank last line, then the terminator) still keeps that real
        // blank line: only the final terminator is removed.
        String body = diffText.endsWith("\n") ? diffText.substring(0, diffText.length() - 1) : diffText;
        String[] raws = body.split("\n", -1);
        for (int i = 0; i < raws.length; i++) {
            // CRLF diffs: strip one trailing \r before any classification,
            // otherwise it leaks into paths and evidence strings.
            String line = strip(raws[i]);
            String next = i + 1 < raws.length ? strip(raws[i + 1]) : "";

            // Old-file marker: either we're between hunks (nothing to confuse
            // it with), or the very next line is its +++ partner.
            if (line.startsWith("--- ") && (!inHunk || next.startsWith("+++ "))) {
                prevWasOldMarker = true;
                inHunk = false;
                continue;
            }
            // New-file marker: its --- partner just went by, or we're between
            // hunks. Otherwise this is a "++ ..." content line and falls
            // through to the generic '+' branch below.
            if (line.startsWith("+++ ") && (prevWasOldMarker || !inHunk)) {
                currentPath = parsePath(line.substring(4));
                prevWasOldMarker = false;
                inHunk = false;
                continue;
            }
            prevWasOldMarker = false;

            // A hunk header is never ambiguous: content lines always carry a
            // +/-/space marker, so nothing inside a hunk can start with "@@ -".
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.find()) {
                newLine = Integer.parseInt(m.group(1));
                sawHunk = true;
                inHunk = true;
                continue;
            }
            // Anything not shaped like a hunk line ("diff --git", "index",
            // "new file mode", "Binary files ... differ") ends the hunk.
            // "\ No newline at end of file" is excluded: it appears inside one.
            if (!isHunkShaped(line)) {
                inHunk = false;
            }
            if (currentPath == null) {
                continue; // deleted file (+++ /dev/null) or preamble — nothing to review
            }
            if (line.startsWith("+")) {
                out.add(new DiffLine(currentPath, newLine, LineType.ADDED, line.substring(1)));
                newLine++;
            } else if (line.startsWith(" ")) {
                out.add(new DiffLine(currentPath, newLine, LineType.CONTEXT, line.substring(1)));
                newLine++;
            } else if (line.startsWith("-")) {
                out.add(new DiffLine(currentPath, null, LineType.REMOVED, line.substring(1)));
            } else if (line.isEmpty()) {
                // Strict unified-diff format prefixes even a blank context
                // line with a single space, but real-world diffs (hand-edited,
                // or passed through a tool that strips trailing whitespace)
                // often emit a fully empty line instead. Treat it the same as
                // a blank CONTEXT line so the new-file counter doesn't drift
                // for the rest of the hunk.
                out.add(new DiffLine(currentPath, newLine, LineType.CONTEXT, ""));
                newLine++;
            }
            // "\ No newline at end of file" and anything else: no counter change.
        }

        if (requireHunk && !sawHunk) {
            throw new InvalidDiffException("diff contains no hunks");
        }
        return out;
    }

    /** One trailing \r removed, so CRLF diffs classify identically to LF ones. */
    private static String strip(String raw) {
        return raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
    }

    /** Could this line be part of a hunk body? */
    private static boolean isHunkShaped(String line) {
        return line.isEmpty()
                || line.startsWith("+") || line.startsWith("-") || line.startsWith(" ")
                || line.startsWith("\\");
    }

    /**
     * Normalizes a +++ path: strips git quotes, cuts a tab-separated classic
     * diff -u timestamp, strips the b/ prefix. /dev/null -> null.
     */
    private String parsePath(String raw) {
        String p = raw;
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab);
        }
        p = p.trim();
        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
            p = p.substring(1, p.length() - 1);
        }
        if (p.equals("/dev/null")) {
            return null;
        }
        if (p.startsWith("b/")) {
            p = p.substring(2);
        }
        return p;
    }
}
