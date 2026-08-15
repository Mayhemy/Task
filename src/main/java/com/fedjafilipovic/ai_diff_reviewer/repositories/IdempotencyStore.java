package com.fedjafilipovic.ai_diff_reviewer.repositories;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Idempotency-Key -> {bodyHash, jobId}. Guards duplicate JOB CREATION.
 * All mutating/checking access is synchronized on this instance so two
 * concurrent same-key requests can never create two jobs. Keys are only
 * stored AFTER a request has validated and its job exists — a request that
 * failed validation (422) leaves no idempotency record and may be retried.
 */
@Component
public class IdempotencyStore {

    public record IdemRecord(String bodyHash, String jobId) {}

    private final Map<String, IdemRecord> records = new HashMap<>();

    public synchronized IdemRecord get(String key) {
        return records.get(key);
    }

    public synchronized void put(String key, IdemRecord record) {
        records.put(key, record);
    }

    /** Monitor used by JobService to make check-validate-create-store atomic. */
    public Object lock() {
        return this;
    }
}
