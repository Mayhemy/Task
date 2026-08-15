package com.fedjafilipovic.ai_diff_reviewer.provider;

import com.fedjafilipovic.ai_diff_reviewer.domain.DiffLine;
import com.fedjafilipovic.ai_diff_reviewer.domain.Finding;
import com.fedjafilipovic.ai_diff_reviewer.domain.LineType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic provider. Rules apply to ADDED lines only (MOCK-004 is the
 * exception — it needs CONTEXT lines for brace matching). One finding per
 * rule per matching line. Interpretation decisions are the agreed "recommended
 * hybrid"; see CLAUDE.md §6 table and SUBMISSION.md.
 *
 * MOCK-003 is case-SENSITIVE (the brief marks MOCK-002 and MOCK-INJ as
 * case-insensitive explicitly; their absence here is meaningful).
 * MOCK-005 excludes === null / !== null decoys via negative lookbehind.
 */
@Component
public class MockReviewProvider implements ReviewProvider {

    // MOCK-002: exactly the regex from the brief, no "improvements".
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(api[_-]?key|secret|token)\\s*[:=]\\s*['\"][A-Za-z0-9_\\-]{16,}['\"]",
            Pattern.CASE_INSENSITIVE);

    // MOCK-003: case-SENSITIVE. \b prevents SELECTION matching.
    private static final Pattern SQL_CONCAT = Pattern.compile(
            "['\"][^'\"]*\\b(SELECT|INSERT|UPDATE|DELETE)\\b[^'\"]*['\"]\\s*\\+" +
            "|\\+\\s*['\"][^'\"]*\\b(SELECT|INSERT|UPDATE|DELETE)\\b[^'\"]*['\"]");

    // MOCK-005: loose null only — excludes === null / !== null via lookbehind.
    private static final Pattern LOOSE_NULL = Pattern.compile("(?<![=!])(==|!=) null");

    // \b prevents identifiers like "mycatch(" or "recatch(" from matching.
    private static final Pattern CATCH_OPEN = Pattern.compile("\\bcatch\\s*\\([^)]*\\)\\s*\\{");

    @Override
    public List<Finding> review(String chunkText, List<DiffLine> lines) throws ProviderException {
        List<Finding> raw = new ArrayList<>();

        // Single-line rules over added lines.
        for (DiffLine dl : lines) {
            if (dl.type() != LineType.ADDED) {
                continue;
            }
            String content = dl.content();
            int line = dl.newLine();

            if (content.contains("eval(")) {
                raw.add(Finding.of("MOCK-001", dl.path(), line, "critical", "security", "eval usage", content));
            }
            if (CREDENTIAL.matcher(content).find()) {
                raw.add(Finding.of("MOCK-002", dl.path(), line, "critical", "security", "hardcoded credential", content));
            }
            if (SQL_CONCAT.matcher(content).find()) {
                raw.add(Finding.of("MOCK-003", dl.path(), line, "high", "security", "SQL string concatenation", content));
            }
            if (LOOSE_NULL.matcher(content).find()) {
                raw.add(Finding.of("MOCK-005", dl.path(), line, "medium", "correctness", "loose null comparison", content));
            }
            if (content.contains("JSON.parse(JSON.stringify(")) {
                raw.add(Finding.of("MOCK-006", dl.path(), line, "medium", "performance", "deep-clone via JSON", content));
            }
            if (content.contains("console.log(")) {
                raw.add(Finding.of("MOCK-007", dl.path(), line, "low", "style", "console.log left in", content));
            }
            if (content.contains("TODO") || content.contains("FIXME")) {
                raw.add(Finding.of("MOCK-008", dl.path(), line, "low", "style", "unresolved marker", content));
            }
            String lower = content.toLowerCase();
            if (lower.contains("ignore previous instructions")
                    || lower.contains("disregard all prior")
                    || lower.contains("you are now")) {
                raw.add(Finding.of("MOCK-INJ", dl.path(), line, "critical", "security", "prompt-injection content", content));
            }
        }

        // MOCK-004: multi-line swallowed-exception scan. Needs CONTEXT lines
        // so brace matching is coherent. Report only when the catch line
        // itself is ADDED.
        raw.addAll(findSwallowedExceptions(lines));

        return raw;
    }

    private List<Finding> findSwallowedExceptions(List<DiffLine> lines) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            DiffLine dl = lines.get(i);
            if (dl.type() == LineType.REMOVED) {
                continue;
            }
            String content = dl.content();
            int openIdx = indexOfCatchOpen(content);
            if (openIdx < 0) {
                continue;
            }
            // Depth from the opening brace onward on this line.
            int depth = 0;
            int openBrace = content.indexOf('{', openIdx);
            if (openBrace < 0) {
                continue;
            }
            for (int c = openBrace; c < content.length(); c++) {
                char ch = content.charAt(c);
                if (ch == '{') depth++;
                else if (ch == '}') depth--;
                if (depth == 0) {
                    // Body entirely on this line.
                    String body = content.substring(openBrace + 1, c);
                    if (isEmptyOrComment(body) && dl.type() == LineType.ADDED) {
                        out.add(Finding.of("MOCK-004", dl.path(), dl.newLine(), "high", "correctness", "swallowed exception", content));
                    }
                    break;
                }
            }
            if (depth == 0) {
                continue; // handled above
            }
            // Body spans following lines.
            StringBuilder body = new StringBuilder(content.substring(openBrace + 1));
            int j = i + 1;
            boolean closed = false;
            while (j < lines.size() && depth > 0) {
                DiffLine next = lines.get(j);
                if (next.type() == LineType.REMOVED) {
                    j++;
                    continue;
                }
                String nc = next.content();
                for (int c = 0; c < nc.length() && depth > 0; c++) {
                    char ch = nc.charAt(c);
                    if (ch == '{') depth++;
                    else if (ch == '}') depth--;
                    if (depth == 0) {
                        body.append('\n').append(nc, 0, c);
                        closed = true;
                    }
                }
                if (depth > 0) {
                    body.append('\n').append(nc);
                }
                j++;
            }
            if (closed && isEmptyOrComment(body.toString()) && dl.type() == LineType.ADDED) {
                out.add(Finding.of("MOCK-004", dl.path(), dl.newLine(), "high", "correctness", "swallowed exception", content));
            }
        }
        return out;
    }

    private int indexOfCatchOpen(String content) {
        java.util.regex.Matcher m = CATCH_OPEN.matcher(content);
        return m.find() ? m.start() : -1;
    }

    /**
     * Empty = blank/whitespace/comment-only. Handles // line comments and a
     * minimal /* ... *\/ span. Does not handle braces inside string literals
     * — documented simplification.
     */
    private boolean isEmptyOrComment(String body) {
        String b = body.trim();
        if (b.isEmpty()) {
            return true;
        }
        // Strip /* ... */ spans (DOTALL: a block comment may span multiple
        // reconstructed lines), then // line comments.
        String stripped = b.replaceAll("(?s)/\\*.*?\\*/", "");
        String[] parts = stripped.split("\n");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("//")) continue;
            return false;
        }
        return true;
    }
}
