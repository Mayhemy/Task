package com.fedjafilipovic.ai_diff_reviewer.models;

/**
 * One line inside a diff hunk.
 *
 * @param path    new-file path (b/ prefix stripped), never null for hunk lines
 * @param newLine line number in the NEW file; null for REMOVED lines
 *                (removed lines exist only in the old file)
 * @param type    added / context / removed
 * @param content line content without the leading +/space/- marker and
 *                without any trailing \r
 */
public record DiffLine(String path, Integer newLine, LineType type, String content) {
}
