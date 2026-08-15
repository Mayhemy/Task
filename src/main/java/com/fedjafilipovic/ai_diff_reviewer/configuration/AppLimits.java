package com.fedjafilipovic.ai_diff_reviewer.configuration;

/**
 * Single source of truth for every enforced limit. /spec, the payload guard,
 * the rate limiter, the chunker, and the executor pool all read from here —
 * nothing is hardcoded twice, so the self-declaration can never drift from
 * actual behavior.
 */
public final class AppLimits {
    public static final long MAX_PAYLOAD_BYTES = 1_048_576;   // 1 MiB, whole request body
    public static final int  CHUNK_BYTES = 65_536;            // 64 KiB
    public static final int  MAX_CONCURRENT_JOBS = 4;
    public static final int  RATE_LIMIT_PER_MINUTE = 30;
    /** Overall safety net across every caller combined, independent of the
     *  per-caller limit above. Not part of /spec's declared shape (which has
     *  a single rateLimitPerMinute field) — an internal guardrail only. */
    public static final int  RATE_LIMIT_HARD_CAP_PER_MINUTE = 120;
    public static final int  DEFAULT_MAX_FINDINGS = 100;
    public static final String VERSION = "1.0.0";
    public static final String SPEC_VERSION = "1.0";

    private AppLimits() {}
}
