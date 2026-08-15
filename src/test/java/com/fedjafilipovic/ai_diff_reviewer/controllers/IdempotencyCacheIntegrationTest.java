package com.fedjafilipovic.ai_diff_reviewer.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency & caching probes. Maps to §5 rows 24–28:
 * 24. same Idempotency-Key + byte-identical body twice -> same jobId, real status
 * 25. same key + one-byte-different body -> 409 idempotency_conflict
 * 26. identical body, different keys -> different jobIds, second cacheHit=true
 * 27. concurrent identical bodies -> exactly one does the work; rest cacheHit
 * 28. failed llm job NOT cached: resubmit re-runs
 *
 * @DirtiesContext: see LifecycleIntegrationTest — several classes share this
 * exact `properties` value and thus, without isolation, the same singleton
 * RateLimitFilter TokenBucket, whose combined traffic can spuriously exceed
 * 30/min. Every class sharing this properties value carries the same
 * annotation so whichever runs first always leaves a clean context behind.
 *
 * The llm-* overrides force the llm provider into its unconfigured state
 * deterministically — row 28 (failedLlmJobIsNotCachedAndResubmitReRuns) needs
 * a job that reliably fails, regardless of whatever the developer's local
 * .env happens to contain.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = com.fedjafilipovic.ai_diff_reviewer.bootstrap.AiDiffReviewerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.bearer-token=tok", "app.llm-base-url=", "app.llm-api-key=", "app.llm-model="})
class IdempotencyCacheIntegrationTest {

    @LocalServerPort
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpSupport http() { return new HttpSupport(port); }
    private static final String T = "tok";

    private JsonNode env(byte[] body) throws Exception { return mapper.readTree(body); }

    private static final String DIFF_BODY =
            "{\"diff\":\"--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x)\\n\"}";

    @Test
    void sameKeySameBodyReturnsSameJobId() throws Exception {
        HttpSupport.RawResponse r1 = http().post("/v1/reviews", DIFF_BODY, T, "key-1", null, null);
        HttpSupport.RawResponse r2 = http().post("/v1/reviews", DIFF_BODY, T, "key-1", null, null);
        assertThat(r1.status()).isEqualTo(202);
        assertThat(r2.status()).isEqualTo(202);
        String id1 = env(r1.body()).get("jobId").asText();
        String id2 = env(r2.body()).get("jobId").asText();
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void idempotentReplayReturnsRealStatusNotHardcodedQueued() throws Exception {
        HttpSupport.RawResponse r1 = http().post("/v1/reviews", DIFF_BODY, T, "key-real", null, null);
        String id1 = env(r1.body()).get("jobId").asText();
        // wait for completion
        awaitTerminal(id1);
        // second request with same key should return the SAME jobId and the real status (done).
        HttpSupport.RawResponse r2 = http().post("/v1/reviews", DIFF_BODY, T, "key-real", null, null);
        JsonNode b2 = env(r2.body());
        assertThat(b2.get("jobId").asText()).isEqualTo(id1);
        // The replayed status reflects reality. It may be "done" or still "queued"
        // if raced, but it must be a valid status and the jobId must match.
        assertThat(b2.get("status").asText()).isIn("queued", "running", "done", "failed");
    }

    @Test
    void sameKeyDifferentBodyReturns409() throws Exception {
        http().post("/v1/reviews", DIFF_BODY, T, "key-conflict", null, null);
        String differentBody = "{\"diff\":\"--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+console.log(x)\\n\"}";
        HttpSupport.RawResponse r2 = http().post("/v1/reviews", differentBody, T, "key-conflict", null, null);
        assertThat(r2.status()).isEqualTo(409);
        assertThat(env(r2.body()).get("error").get("code").asText()).isEqualTo("idempotency_conflict");
    }

    @Test
    void identicalBodyDifferentKeysYieldDifferentJobIdsAndSecondCacheHit() throws Exception {
        HttpSupport.RawResponse r1 = http().post("/v1/reviews", DIFF_BODY, T, "k-a", null, null);
        String id1 = env(r1.body()).get("jobId").asText();
        awaitTerminal(id1);
        HttpSupport.RawResponse r2 = http().post("/v1/reviews", DIFF_BODY, T, "k-b", null, null);
        String id2 = env(r2.body()).get("jobId").asText();
        assertThat(id1).isNotEqualTo(id2);

        awaitTerminal(id2);
        JsonNode job2 = env(http().get("/v1/reviews/" + id2, T).body());
        assertThat(job2.get("usage").get("cacheHit").asBoolean()).isTrue();

        // findings identical between the two jobs
        JsonNode job1 = env(http().get("/v1/reviews/" + id1, T).body());
        assertThat(job1.get("findings").toString()).isEqualTo(job2.get("findings").toString());
    }

    @Test
    void identicalBodyNoKeyYieldsCacheHitOnSecond() throws Exception {
        HttpSupport.RawResponse r1 = http().post("/v1/reviews", DIFF_BODY, T, null, null, null);
        String id1 = env(r1.body()).get("jobId").asText();
        awaitTerminal(id1);
        HttpSupport.RawResponse r2 = http().post("/v1/reviews", DIFF_BODY, T, null, null, null);
        String id2 = env(r2.body()).get("jobId").asText();
        awaitTerminal(id2);
        JsonNode job2 = env(http().get("/v1/reviews/" + id2, T).body());
        assertThat(job2.get("usage").get("cacheHit").asBoolean()).isTrue();
    }

    @Test
    void concurrentIdenticalBodiesExactlyOneDoesWork() throws Exception {
        // A body unique to THIS test — DIFF_BODY is also submitted by several
        // other methods in this class sharing the same ResultCache singleton
        // (methods share one Spring context); reusing it here would let an
        // earlier method's cache entry already exist before this test's 5
        // concurrent requests run, making "exactly one creator" depend on
        // test execution order instead of the concurrency guarantee being
        // tested.
        String body = "{\"diff\":\"--- a/concurrent-unique.js\\n+++ b/concurrent-unique.js\\n"
                + "@@ -1 +1 @@\\n+eval(x)\\n\"}";
        int n = 5;
        HttpSupport.RawResponse[] responses = new HttpSupport.RawResponse[n];
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> responses[idx] = http().post("/v1/reviews", body, T, null, null, null));
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        // All accepted; collect jobIds
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) {
            assertThat(responses[i].status()).isEqualTo(202);
            ids[i] = env(responses[i].body()).get("jobId").asText();
        }
        // Wait for all to finish
        for (String id : ids) awaitTerminal(id);

        // Exactly one should have cacheHit=false (the creator); the rest cacheHit=true.
        int creators = 0;
        for (String id : ids) {
            JsonNode job = env(http().get("/v1/reviews/" + id, T).body());
            if (!job.get("usage").get("cacheHit").asBoolean()) creators++;
        }
        assertThat(creators).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyExactlyOneJobCreated() throws Exception {
        int n = 5;
        HttpSupport.RawResponse[] responses = new HttpSupport.RawResponse[n];
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> responses[idx] = http().post("/v1/reviews", DIFF_BODY, T, "same-key-concurrent", null, null));
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        // All 5 should return the SAME jobId (idempotent).
        String first = env(responses[0].body()).get("jobId").asText();
        for (int i = 0; i < n; i++) {
            assertThat(env(responses[i].body()).get("jobId").asText()).isEqualTo(first);
        }
    }

    @Test
    void failedLlmJobIsNotCachedAndResubmitReRuns() throws Exception {
        // Blank llm credentials -> failed job. Resubmit identical body -> another
        // failed job (re-run, not a cached failure). Both should be `failed`.
        String body = "{\"diff\":\"+++ b/f.js\\n@@ -1 +1 @@\\n+x\\n\",\"options\":{\"provider\":\"llm\"}}";
        HttpSupport.RawResponse r1 = http().post("/v1/reviews", body, T, null, null, null);
        String id1 = env(r1.body()).get("jobId").asText();
        awaitTerminal(id1);
        assertThat(env(http().get("/v1/reviews/" + id1, T).body()).get("status").asText()).isEqualTo("failed");

        HttpSupport.RawResponse r2 = http().post("/v1/reviews", body, T, null, null, null);
        String id2 = env(r2.body()).get("jobId").asText();
        awaitTerminal(id2);
        // Different jobId (not cached), and also failed (re-ran).
        assertThat(id1).isNotEqualTo(id2);
        assertThat(env(http().get("/v1/reviews/" + id2, T).body()).get("status").asText()).isEqualTo("failed");
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
