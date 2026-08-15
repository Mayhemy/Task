package com.fedjafilipovic.ai_diff_reviewer.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum JobStatus {
    QUEUED, RUNNING, DONE, FAILED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
