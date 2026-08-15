package com.fedjafilipovic.ai_diff_reviewer.services;

import com.fedjafilipovic.ai_diff_reviewer.exceptions.InvalidDiffException;
import com.fedjafilipovic.ai_diff_reviewer.models.DiffLine;
import com.fedjafilipovic.ai_diff_reviewer.models.LineType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins DiffParser line-numbering, path normalization, CRLF handling, and the
 * no-hunks 422 trigger. Maps to §5 rows 12, 15, 18.
 */
class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    private static final String SIMPLE = """
            diff --git a/foo.js b/foo.js
            index 111..222 100644
            --- a/foo.js
            +++ b/foo.js
            @@ -1,2 +1,3 @@
              ctx
            -old
            +new
            +added
            """;

    @Test
    void parsesAddedContextRemovedWithExactNewFileLineNumbers() {
        List<DiffLine> lines = parser.parse(SIMPLE);

        // Hunk starts at new-file line 1: ctx=1, old(removed, no advance), new=2, added=3
        assertThat(lines).extracting(DiffLine::type)
                .containsExactly(LineType.CONTEXT, LineType.REMOVED, LineType.ADDED, LineType.ADDED);
        assertThat(lines).extracting(DiffLine::newLine)
                .containsExactly(1, null, 2, 3);
        assertThat(lines).extracting(DiffLine::path)
                .containsOnly("foo.js");
        // content has no leading marker and no trailing \r
        assertThat(lines.get(2).content()).isEqualTo("new");
        assertThat(lines.get(3).content()).isEqualTo("added");
    }

    @Test
    void multipleHunksEachResetCounterFromTheirOwnHeader() {
        String diff = """
                --- a/f.js
                +++ b/f.js
                @@ -1,1 +5,1 @@
                +a
                @@ -10,1 +20,1 @@
                +b
                """;
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).extracting(DiffLine::newLine).containsExactly(5, 20);
    }

    @Test
    void shortFormHunkHeaderWithoutCounts() {
        String diff = """
                --- a/f.js
                +++ b/f.js
                @@ -1 +1 @@
                +x
                """;
        assertThat(parser.parse(diff)).extracting(DiffLine::newLine).containsExactly(1);
    }

    @Test
    void trailingSectionContextAfterSecondAtAtIsIgnored() {
        String diff = """
                --- a/f.js
                +++ b/f.js
                @@ -1 +1 @@ function
                +x
                """;
        assertThat(parser.parse(diff)).extracting(DiffLine::newLine).containsExactly(1);
    }

    @Test
    void crlfDiffStripsTrailingCarriageReturnFromEveryLine() {
        // A hunk line only counts once a +++ path is in effect; include one
        // (also CRLF-terminated) so the added line is actually collected.
        String crlf = "--- a/f.js\r\n+++ b/f.js\r\n@@ -1 +1 @@\r\n+eval(x)\r\n";
        List<DiffLine> lines = parser.parse(crlf);
        assertThat(lines.get(0).content()).isEqualTo("eval(x)");
        assertThat(lines.get(0).content()).doesNotEndWith("\r");
    }

    @Test
    void crlfPathHasNoCarriageReturn() {
        String crlf = "--- a/f.js\r\n+++ b/f.js\r\n@@ -1 +1 @@\r\n+x\r\n";
        List<DiffLine> lines = parser.parse(crlf);
        assertThat(lines.get(0).path()).isEqualTo("f.js");
    }

    @Test
    void gitQuotedPathIsUnwrapped() {
        // Real git quoting wraps the WHOLE a/- or b/-prefixed path, e.g.
        // +++ "b/file with spaces.js" — not b/"file with spaces.js".
        String diff = "--- \"a/file with spaces.js\"\n+++ \"b/file with spaces.js\"\n@@ -1 +1 @@\n+x\n";
        assertThat(parser.parse(diff).get(0).path()).isEqualTo("file with spaces.js");
    }

    @Test
    void bPrefixStripped() {
        String diff = "--- a/x.js\n+++ b/x.js\n@@ -1 +1 @@\n+x\n";
        assertThat(parser.parse(diff).get(0).path()).isEqualTo("x.js");
    }

    @Test
    void classicDiffUTabTimestampCutFromPath() {
        String diff = "--- f.js\t2024-01-01\n+++ f.js\t2024-01-02\n@@ -1 +1 @@\n+x\n";
        assertThat(parser.parse(diff).get(0).path()).isEqualTo("f.js");
    }

    @Test
    void newFileDevNullYieldsAllHunkLinesAsAdded() {
        // --- /dev/null => new file; every hunk line is added.
        String diff = "--- /dev/null\n+++ new.js\n@@ -0,0 +1,2 @@\n+a\n+b\n";
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).extracting(DiffLine::type).containsExactly(LineType.ADDED, LineType.ADDED);
        assertThat(lines).extracting(DiffLine::newLine).containsExactly(1, 2);
    }

    @Test
    void deletedFileDevNullYieldsZeroAddedLines() {
        // +++ /dev/null => deleted file; currentPath becomes null, so nothing
        // in that hunk (added OR removed) is recorded — "nothing to review"
        // applies to the whole hunk, not just added lines. Valid diff (has
        // hunks), zero DiffLines.
        String diff = "--- old.js\n+++ /dev/null\n@@ -1,1 +0,0 @@\n-old\n";
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).isEmpty();
    }

    @Test
    void hunkWithNoPrecedingFileHeaderYieldsNoLines() {
        // A hunk that appears with NO "--- "/"+++ " lines before it at all
        // (not even a +++ /dev/null) is a distinct code path from
        // deletedFileDevNullYieldsZeroAddedLines above: currentPath is never
        // assigned rather than explicitly set to null. sawHunk still becomes
        // true (a valid diff — no InvalidDiffException), but every hunk line
        // is silently dropped since there's no path to attach it to. Real
        // unified diffs always carry file headers before a hunk; this pins
        // the intentional "nothing to review" behavior rather than a crash
        // or a NullPointerException surfacing as a path later.
        String diff = "@@ -1 +1 @@\n+eval(x)\n";
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).isEmpty();
    }

    @Test
    void noNewlineMarkerDoesNotShiftCounter() {
        // The "no newline" marker is its own standalone line starting with
        // '\' — it is NOT prefixed with '+' (that would make it an added
        // line whose content happens to start with a backslash).
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1,2 +1,2 @@\n+a\n\\ No newline at end of file\n";
        // Only the first line is added at line 1; the \ marker is ignored.
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).type()).isEqualTo(LineType.ADDED);
        assertThat(lines.get(0).newLine()).isEqualTo(1);
    }

    @Test
    void blankContextLineWithoutLeadingSpaceStillAdvancesCounter() {
        // Strict unified-diff format requires even a blank context line to
        // carry a leading space, but real-world diffs sometimes emit a fully
        // empty line instead (e.g. trailing-whitespace-stripping tools). The
        // counter must still advance so the line AFTER the blank one gets the
        // correct new-file number.
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1,3 +1,3 @@\n ctx1\n\n+added\n";
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).extracting(DiffLine::type)
                .containsExactly(LineType.CONTEXT, LineType.CONTEXT, LineType.ADDED);
        assertThat(lines).extracting(DiffLine::newLine).containsExactly(1, 2, 3);
        assertThat(lines.get(1).content()).isEmpty();
    }

    @Test
    void trailingNewlineDoesNotProduceAPhantomBlankLine() {
        // diffText.split("\n", -1) always yields a trailing "" artifact when
        // the text ends with '\n' (virtually always true here) — now that a
        // genuinely empty line is meaningful (see the test above), that
        // artifact must be stripped before iterating, not counted as an
        // extra blank context line.
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1,2 +1,2 @@\n ctx\n+added\n";
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).hasSize(2);
    }

    @Test
    void trailingRealBlankLineBeforeFinalNewlineIsStillCaptured() {
        // "...\n\n" = a real blank last line, then the terminator. Only the
        // terminator should be stripped; the genuine blank line must remain.
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1,2 +1,2 @@\n+added\n\n";
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).extracting(DiffLine::type).containsExactly(LineType.ADDED, LineType.CONTEXT);
        assertThat(lines.get(1).newLine()).isEqualTo(2);
    }

    @Test
    void blankPreambleAndIndexLinesAreIgnored() {
        String diff = "diff --git a/f.js b/f.js\nindex abc..def 100644\nold mode 100644\n--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+x\n";
        List<DiffLine> lines = parser.parse(diff);
        assertThat(lines).hasSize(1);
    }

    @Test
    void noHunksThrowsInvalidDiff() {
        // A pure rename / binary-only diff has no hunks -> 422.
        String rename = "diff --git a/a.js b/b.js\nsimilarity index 100%\nrename from a.js\nrename to b.js\n";
        assertThatThrownBy(() -> parser.parse(rename))
                .isInstanceOf(InvalidDiffException.class);
    }

    @Test
    void emptyStringThrowsInvalidDiff() {
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(InvalidDiffException.class);
    }

    @Test
    void plainUnifiedDiffWithoutGitMarker() {
        // No "diff --git " => split on "--- " style; parser still works.
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+x\n";
        assertThat(parser.parse(diff)).hasSize(1);
    }

    @Test
    void evidenceIsVerbatimIncludingLeadingWhitespace() {
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+    indented();\n";
        assertThat(parser.parse(diff).get(0).content()).isEqualTo("    indented();");
    }
}
