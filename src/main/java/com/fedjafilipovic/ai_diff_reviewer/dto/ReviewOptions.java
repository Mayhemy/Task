package com.fedjafilipovic.ai_diff_reviewer.dto;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;

public record ReviewOptions(String provider, int maxFindings) {

    public static final String PROVIDER_MOCK = "mock";
    public static final String PROVIDER_LLM = "llm";

    public static ReviewOptions defaults() {
        return new ReviewOptions(PROVIDER_MOCK, AppLimits.DEFAULT_MAX_FINDINGS);
    }
}
