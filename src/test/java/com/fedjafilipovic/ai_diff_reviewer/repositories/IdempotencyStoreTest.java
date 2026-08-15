package com.fedjafilipovic.ai_diff_reviewer.repositories;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins IdempotencyStore basic put/get and the lock() monitor identity. */
class IdempotencyStoreTest {

    @Test
    void putAndGetRoundTrip() {
        IdempotencyStore store = new IdempotencyStore();
        assertThat(store.get("missing")).isNull();
        store.put("k1", new IdempotencyStore.IdemRecord("hash1", "job1"));
        IdempotencyStore.IdemRecord r = store.get("k1");
        assertThat(r).isNotNull();
        assertThat(r.bodyHash()).isEqualTo("hash1");
        assertThat(r.jobId()).isEqualTo("job1");
    }

    @Test
    void putOverwritesExistingKey() {
        IdempotencyStore store = new IdempotencyStore();
        store.put("k", new IdempotencyStore.IdemRecord("h1", "j1"));
        store.put("k", new IdempotencyStore.IdemRecord("h2", "j2"));
        assertThat(store.get("k").jobId()).isEqualTo("j2");
    }

    @Test
    void lockReturnsStableMonitor() {
        IdempotencyStore store = new IdempotencyStore();
        assertThat(store.lock()).isSameAs(store.lock());
        assertThat(store.lock()).isSameAs(store);
    }
}
