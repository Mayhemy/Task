package com.fedjafilipovic.ai_diff_reviewer.repositories;

import com.fedjafilipovic.ai_diff_reviewer.dto.Finding;
import com.fedjafilipovic.ai_diff_reviewer.dto.Usage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards duplicate WORK, independent of any idempotency key. Keyed on the
 * SHA-256 of the raw request body bytes ("byte-identical {diff, options}").
 *
 * Values are futures, not results: a concurrent byte-identical submission
 * attaches to the in-flight scan instead of redoing it (cacheHit=true).
 * Only successful results stay cached — on failure the entry is removed so a
 * later retry re-runs (a model outage must not poison the cache).
 */
@Component
public class ResultCache {

    public record ScanResult(List<Finding> findings, Usage usage) {}

    private final ConcurrentHashMap<String, CompletableFuture<ScanResult>> cache = new ConcurrentHashMap<>();

    /**
     * @return the existing or newly-created future, and whether this caller
     *         created it (true = this caller must do the work and complete it)
     */
    public Lookup getOrCreate(String bodyHash) {
        CompletableFuture<ScanResult> fresh = new CompletableFuture<>();
        CompletableFuture<ScanResult> existing = cache.putIfAbsent(bodyHash, fresh);
        if (existing == null) {
            return new Lookup(fresh, true);
        }
        return new Lookup(existing, false);
    }

    public void remove(String bodyHash, CompletableFuture<ScanResult> future) {
        cache.remove(bodyHash, future);
    }

    public record Lookup(CompletableFuture<ScanResult> future, boolean created) {}
}
