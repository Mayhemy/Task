package com.fedjafilipovic.ai_diff_reviewer.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle probes. Maps to §5 rows 22, 23 and the §6.10 inputBytes regression.
 * 22. POST -> poll GET -> done well under 30s
 * 23. GET unknown jobId -> 404 envelope; /stream unknown jobId -> 404 envelope JSON
 *
 * @DirtiesContext: several test classes share this exact `properties` value,
 * so without isolation Spring would reuse the same cached context — and with
 * it, the same singleton RateLimitFilter TokenBucket — across all of them.
 * Their combined POST volume can exceed the 30/min bucket and cause spurious
 * 429s unrelated to what each class is actually testing. Every class sharing
 * this properties value is annotated the same way, so whichever runs first
 * always leaves a clean context for the next.
 *
 * The llm-* overrides force the llm provider into its unconfigured state
 * deterministically (this class's failedJobReturns200WithStatusFailedAndErrorField
 * test relies on that) — without them, this test's outcome would depend on
 * whatever the developer's local .env file happens to contain, which is
 * exactly the kind of environment coupling that made it silently break the
 * moment real LLM credentials were configured locally.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = com.fedjafilipovic.ai_diff_reviewer.bootstrap.AiDiffReviewerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.bearer-token=tok", "app.llm-base-url=", "app.llm-api-key=", "app.llm-model="})
class LifecycleIntegrationTest {

    @LocalServerPort
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpSupport http() { return new HttpSupport(port); }
    private static final String T = "tok";

    private JsonNode env(byte[] body) throws Exception { return mapper.readTree(body); }

    private static final String DIFF_JSON =
            "{\"diff\":\"--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x)\\n\"}";

    @Test
    void postThenPollReachesDoneUnder30s() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF_JSON, T, null, null, null);
        assertThat(r.status()).isEqualTo(202);
        String jobId = env(r.body()).get("jobId").asText();

        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode job = null;
        while (System.currentTimeMillis() < deadline) {
            job = env(http().get("/v1/reviews/" + jobId, T).body());
            String s = job.get("status").asText();
            if ("done".equals(s) || "failed".equals(s)) break;
            Thread.sleep(50);
        }
        assertThat(job).isNotNull();
        assertThat(job.get("status").asText()).isEqualTo("done");
        // findings present and non-empty for an eval diff
        assertThat(job.get("findings").size()).isGreaterThan(0);
        assertThat(job.get("findings").get(0).get("ruleId").asText()).isEqualTo("MOCK-001");
        // usage present from creation; inputBytes is the diff's UTF-8 byte length (§6.10)
        String diff = "--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+eval(x)\n";
        assertThat(job.get("usage").get("inputBytes").asLong())
                .isEqualTo(diff.getBytes(StandardCharsets.UTF_8).length);
        assertThat(job.get("usage").get("chunks").asInt()).isEqualTo(1);
        assertThat(job.get("usage").get("cacheHit").asBoolean()).isFalse();
        // no error field on a successful job
        assertThat(job.has("error")).isFalse();
    }

    @Test
    void getJobResponseShape() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF_JSON, T, null, null, null);
        String jobId = env(r.body()).get("jobId").asText();
        awaitTerminal(jobId);
        JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
        assertThat(job.get("jobId").asText()).isEqualTo(jobId);
        assertThat(job.get("status").asText()).isIn("done", "failed");
        assertThat(job.has("findings")).isTrue();
        assertThat(job.has("usage")).isTrue();
    }

    @Test
    void getUnknownJobReturns404Envelope() throws Exception {
        HttpSupport.RawResponse r = http().get("/v1/reviews/does-not-exist", T);
        assertThat(r.status()).isEqualTo(404);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("not_found");
    }

    @Test
    void streamUnknownJobReturns404EnvelopeJson() throws Exception {
        HttpSupport.RawResponse r = http().request("GET", "/v1/reviews/does-not-exist/stream",
                null, T, null, null, null);
        assertThat(r.status()).isEqualTo(404);
        // Must be JSON envelope, not an SSE stream.
        assertThat(r.headers().getOrDefault("Content-Type", "")).doesNotContain("text/event-stream");
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("not_found");
    }

    @Test
    void usagePresentFromCreationBeforeDone() throws Exception {
        // Immediately after POST, a GET should show usage with inputBytes/chunks even
        // if the job is still queued/running.
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF_JSON, T, null, null, null);
        String jobId = env(r.body()).get("jobId").asText();
        JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
        assertThat(job.get("usage").get("inputBytes").asLong()).isGreaterThan(0);
        assertThat(job.get("usage").get("chunks").asInt()).isEqualTo(1);
    }

    @Test
    void failedJobReturns200WithStatusFailedAndErrorField() throws Exception {
        // llm provider with blank credentials -> failed job, but HTTP 200 on GET.
        String body = "{\"diff\":\"" + "+++ b/f.js\\n@@ -1 +1 @@\\n+x\\n"
                + "\",\"options\":{\"provider\":\"llm\"}}";
        HttpSupport.RawResponse r = http().post("/v1/reviews", body, T, null, null, null);
        assertThat(r.status()).isEqualTo(202);
        String jobId = env(r.body()).get("jobId").asText();
        awaitTerminal(jobId);
        JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
        assertThat(r.status()).isEqualTo(202); // POST stays 202
        assertThat(job.get("status").asText()).isEqualTo("failed");
        assertThat(job.get("error").get("message").asText()).contains("not configured");
        // GET returns 200 (failure is a job outcome, not an HTTP error)
        assertThat(http().get("/v1/reviews/" + jobId, T).status()).isEqualTo(200);
    }

    @Test
    void hunkWithNoFileHeaderCompletesWithZeroFindingsNotAnError() throws Exception {
        // A diff with a valid @@ hunk but no preceding "--- "/"+++ " lines is
        // still a "valid diff" (has a hunk -> 202, not 422) but DiffParser
        // never learns a file path for it, so nothing gets reviewed. This
        // pins the end-to-end shape (done, zero findings, no crash) of the
        // unit-level behavior in DiffParserTest#hunkWithNoPrecedingFileHeaderYieldsNoLines.
        String body = "{\"diff\":\"@@ -1 +1 @@\\n+eval(x)\\n\"}";
        HttpSupport.RawResponse r = http().post("/v1/reviews", body, T, null, null, null);
        assertThat(r.status()).isEqualTo(202);
        String jobId = env(r.body()).get("jobId").asText();
        awaitTerminal(jobId);
        JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
        assertThat(job.get("status").asText()).isEqualTo("done");
        assertThat(job.get("findings")).isEmpty();
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
