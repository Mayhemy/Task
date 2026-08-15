package com.fedjafilipovic.ai_diff_reviewer.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate-limit probes. Maps to §5 rows 29, 30.
 *
 * AppLimits.RATE_LIMIT_PER_MINUTE (30) is a hardcoded Java constant, not a
 * Spring-bound property — it is the single source of truth /spec also reads
 * from, so it is intentionally NOT configurable per-test (a second
 * configuration path would risk /spec drifting from actual enforcement).
 * These tests therefore exercise the real fixed limit directly: 31 rapid
 * requests, each with a distinct Idempotency-Key so they're independent
 * submissions, asserting the first 30 succeed and the 31st is rate limited.
 *
 * RateLimitFilter's TokenBucket is a process-wide singleton, and Spring caches
 * @SpringBootTest contexts by configuration signature — reused across both
 * test methods here and other test classes with identical `properties`.
 * Without isolation, a bucket drained by an earlier method/class would make
 * these exact-boundary assertions flaky. The unique marker property keeps
 * this class off the shared cached context other test classes use, and
 * @DirtiesContext forces a brand-new context (and therefore a brand-new,
 * full 30-token bucket) before every single test method in this class.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest(classes = com.fedjafilipovic.ai_diff_reviewer.bootstrap.AiDiffReviewerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.bearer-token=tok", "ratelimit.test.isolation=rate-limit-suite"})
class RateLimitConcurrencyIntegrationTest {

    @LocalServerPort
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpSupport http() { return new HttpSupport(port); }
    private static final String T = "tok";

    private JsonNode env(byte[] body) throws Exception { return mapper.readTree(body); }

    private static final String DIFF_BODY =
            "{\"diff\":\"--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x)\\n\"}";

    @Test
    void thirtyFirstRequestReturns429() throws Exception {
        // The bucket starts full at capacity 30 (AppLimits.RATE_LIMIT_PER_MINUTE):
        // the first 30 requests in this window must all succeed.
        for (int i = 0; i < 30; i++) {
            HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF_BODY, T, "rl-key-" + i, null, null);
            assertThat(r.status()).as("request #%d", i).isEqualTo(202);
        }
        // The 31st exceeds the burst capacity.
        HttpSupport.RawResponse over = http().post("/v1/reviews", DIFF_BODY, T, "rl-key-over", null, null);
        assertThat(over.status()).isEqualTo(429);
    }

    @Test
    void rateLimitedResponseIsEnvelopeWithRetryAfter() throws Exception {
        for (int i = 0; i < 30; i++) {
            http().post("/v1/reviews", DIFF_BODY, T, "fill-" + i, null, null);
        }
        HttpSupport.RawResponse over = http().post("/v1/reviews", DIFF_BODY, T, "over-limit", null, null);
        assertThat(over.status()).isEqualTo(429);
        JsonNode b = env(over.body());
        assertThat(b.get("error").get("code").asText()).isEqualTo("rate_limited");
        assertThat(over.headers()).containsKey("Retry-After");
    }

    @Test
    void unauthenticatedRequestsNeverConsumeTheRateLimitBucket() throws Exception {
        // Auth (401) runs before the rate limiter, so bad-token requests must
        // never exhaust the bucket, and a subsequent authenticated burst must
        // still get the full 30-request allowance.
        for (int i = 0; i < 5; i++) {
            assertThat(http().post("/v1/reviews", DIFF_BODY, "wrong-token", "bad-" + i, null, null).status())
                    .isEqualTo(401);
        }
        for (int i = 0; i < 30; i++) {
            HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF_BODY, T, "after-bad-" + i, null, null);
            assertThat(r.status()).as("request #%d", i).isEqualTo(202);
        }
        assertThat(http().post("/v1/reviews", DIFF_BODY, T, "after-bad-over", null, null).status())
                .isEqualTo(429);
    }

    @Test
    void rateLimitPrecedenceOverPayloadSize() throws Exception {
        // A rate-limited caller sending an oversized body should get 429, not 413.
        for (int i = 0; i < 30; i++) {
            http().post("/v1/reviews", DIFF_BODY, T, "prec-" + i, null, null);
        }
        HttpSupport.RawResponse r = http().post("/v1/reviews", "x".repeat(2_000_000), T, "prec-over", null, null);
        assertThat(r.status()).isEqualTo(429);
    }

    @Test
    void concurrentSubmissionsAllEventuallyReachTerminalStateNoneRejected() throws Exception {
        // §5 row 30: at least 4 jobs process concurrently; a queued 5th+ must
        // not fail. Fire 6 simultaneous submissions and confirm all are
        // accepted and all eventually reach a terminal status.
        int n = 6;
        Thread[] threads = new Thread[n];
        String[] jobIds = new String[n];
        HttpSupport.RawResponse[] responses = new HttpSupport.RawResponse[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> responses[idx] =
                    http().post("/v1/reviews", DIFF_BODY, T, "conc-" + idx, null, null));
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        for (int i = 0; i < n; i++) {
            assertThat(responses[i].status()).isEqualTo(202);
            jobIds[i] = env(responses[i].body()).get("jobId").asText();
        }
        for (String jobId : jobIds) {
            awaitTerminal(jobId);
        }
    }

    private void awaitTerminal(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
            String s = job.get("status").asText();
            if ("done".equals(s) || "failed".equals(s)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " did not terminate");
    }
}
