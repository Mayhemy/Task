package com.fedjafilipovic.ai_diff_reviewer.diff;

import com.fedjafilipovic.ai_diff_reviewer.domain.DiffLine;
import com.fedjafilipovic.ai_diff_reviewer.domain.LineType;
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
        List<DiffLine> out = new ArrayList<>();
        String currentPath = null;
        int newLine = 0;
        boolean sawHunk = false;

        // split("\n", -1) yields a trailing "" artifact whenever diffText ends
        // with '\n' (virtually always). Now that a genuinely empty line is
        // meaningful (treated as a blank context line below), that artifact
        // must not be iterated as if it were a real line — strip exactly the
        // one trailing '\n' that produced it first. A text ending in "\n\n"
        // (a real blank last line, then the terminator) still keeps that real
        // blank line: only the final terminator is removed.
        String body = diffText.endsWith("\n") ? diffText.substring(0, diffText.length() - 1) : diffText;
        for (String raw : body.split("\n", -1)) {
            // CRLF diffs: strip one trailing \r before any classification,
            // otherwise it leaks into paths and evidence strings.
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;

            // +++ must be checked BEFORE the generic '+' branch.
            if (line.startsWith("+++ ")) {
                currentPath = parsePath(line.substring(4));
                continue;
            }
            if (line.startsWith("--- ")) {
                continue; // old-file marker, ignored
            }
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.find()) {
                newLine = Integer.parseInt(m.group(1));
                sawHunk = true;
                continue;
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

        if (!sawHunk) {
            throw new InvalidDiffException("diff contains no hunks");
        }
        return out;
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
