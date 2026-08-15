package com.fedjafilipovic.ai_diff_reviewer.dto;

public record Finding(String id, String ruleId, String path, int line,
                      String severity, String category, String title, String evidence) {

    public static Finding of(String ruleId, String path, int line, String severity,
                             String category, String title, String evidence) {
        return new Finding(ruleId + ":" + path + ":" + line,
                ruleId, path, line, severity, category, title, evidence);
    }
}
