package com.fedjafilipovic.ai_diff_reviewer.domain;

/**
 * @param inputBytes UTF-8 byte length of the diff string (not the request body)
 * @param chunks     number of chunks the diff was split into, always >= 1
 * @param cacheHit   true when the scan result was reused instead of recomputed
 */
public record Usage(long inputBytes, int chunks, boolean cacheHit) {

    public Usage withCacheHit(boolean hit) {
        return new Usage(inputBytes, chunks, hit);
    }
}
