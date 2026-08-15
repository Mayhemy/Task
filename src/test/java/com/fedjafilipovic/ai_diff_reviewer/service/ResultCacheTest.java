package com.fedjafilipovic.ai_diff_reviewer.service;

import com.fedjafilipovic.ai_diff_reviewer.domain.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins ResultCache in-flight attachment, created-flag semantics, and removal. */
class ResultCacheTest {

    private static ResultCache.ScanResult result() {
        return new ResultCache.ScanResult(List.of(), new Usage(1, 1, false));
    }

    @Test
    void firstLookupCreatesAndReportsCreated() {
        ResultCache cache = new ResultCache();
        ResultCache.Lookup l = cache.getOrCreate("h");
        assertThat(l.created()).isTrue();
        assertThat(l.future()).isNotDone();
    }

    @Test
    void secondLookupAttachesToSameFutureNotCreated() {
        ResultCache cache = new ResultCache();
        ResultCache.Lookup first = cache.getOrCreate("h");
        ResultCache.Lookup second = cache.getOrCreate("h");
        assertThat(second.created()).isFalse();
        assertThat(second.future()).isSameAs(first.future());
    }

    @Test
    void removeOnlyRemovesIfSameFuture() {
        ResultCache cache = new ResultCache();
        ResultCache.Lookup l = cache.getOrCreate("h");
        // Removing with a different future must not evict the real entry.
        cache.remove("h", new CompletableFuture<>());
        assertThat(cache.getOrCreate("h").created()).isFalse();
        // Removing with the real future evicts it.
        cache.remove("h", l.future());
        assertThat(cache.getOrCreate("h").created()).isTrue();
    }

    @Test
    void concurrentGetOrCreateYieldsExactlyOneCreator() throws Exception {
        ResultCache cache = new ResultCache();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger creators = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (cache.getOrCreate("same").created()) {
                        creators.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(creators.get()).isEqualTo(1);
    }

    @Test
    void completedFutureIsReusedByLateAttacher() {
        ResultCache cache = new ResultCache();
        ResultCache.Lookup l = cache.getOrCreate("h");
        l.future().complete(result());
        ResultCache.Lookup late = cache.getOrCreate("h");
        assertThat(late.created()).isFalse();
        assertThat(late.future().join().usage().inputBytes()).isEqualTo(1);
    }
}
