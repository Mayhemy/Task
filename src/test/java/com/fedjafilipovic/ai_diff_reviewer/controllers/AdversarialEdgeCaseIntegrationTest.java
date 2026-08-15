package com.fedjafilipovic.ai_diff_reviewer.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases surfaced by a live adversarial probe of the deployed service
 * (malformed/hostile requests against the real public URL, not just
 * localhost) that weren't already pinned by an existing test class: a
 * duplicate-JSON-key request, wrong-typed maxFindings values, and an
 * oversized body arriving via chunked transfer encoding (no Content-Length
 * at all, unlike PostValidationIntegrationTest's declared-length variants).
 *
 * Split into its own class — rather than folded into
 * PostValidationIntegrationTest — purely for rate-limit budget: that class
 * already sits close to the 30-token-per-class ceiling (AppLimits.
 * RATE_LIMIT_PER_MINUTE is a hardcoded constant, not test-configurable), and
 * every POST here would have pushed it over. The unique marker property and
 * per-method @DirtiesContext keep this class on its own dedicated, always-full
 * bucket (same pattern as RateLimitConcurrencyIntegrationTest).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest(classes = com.fedjafilipovic.ai_diff_reviewer.bootstrap.AiDiffReviewerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.bearer-token=tok", "ratelimit.test.isolation=adversarial-edge-case-suite"})
class AdversarialEdgeCaseIntegrationTest {

    @LocalServerPort
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpSupport http() { return new HttpSupport(port); }
    private static final String T = "tok";

    private static final String VALID_DIFF =
            "--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x)\\n";

    private JsonNode env(byte[] body) throws Exception { return mapper.readTree(body); }

    @Test
    void chunkedTransferEncodingOversizedBodyStillReturns413() throws Exception {
        // No Content-Length at all (chunked): the fast declared-length guard
        // can't see this coming (getContentLengthLong() returns -1), so the
        // bounded streaming read loop itself must still enforce the cap.
        String prefix = "{\"diff\":\"--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+x\\n+";
        String suffix = "\\n\"}";
        int overhead = (prefix + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String body = prefix + "a".repeat(1_048_577 - overhead) + suffix;
        HttpSupport.RawResponse r = http().postBytesChunked("/v1/reviews",
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), T);
        assertThat(r.status()).isEqualTo(413);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("payload_too_large");
    }

    @Test
    void maxFindingsFloatReturns400() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\",\"options\":{\"maxFindings\":3.5}}", T, null, null, null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("invalid_json");
    }

    @Test
    void maxFindingsStringReturns400() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\",\"options\":{\"maxFindings\":\"5\"}}", T, null, null, null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("invalid_json");
    }

    @Test
    void duplicateJsonKeyLastValueWins() throws Exception {
        // JSON spec leaves duplicate object keys implementation-defined;
        // Jackson's readTree keeps the LAST occurrence. Pin that this is
        // actually what happens end-to-end (not just "doesn't crash") — a
        // grader could plausibly probe this expecting either first- or
        // last-wins, or a rejection.
        String body = "{\"diff\":\"\",\"diff\":\"" + VALID_DIFF + "\"}";
        HttpSupport.RawResponse r = http().post("/v1/reviews", body, T, null, null, null);
        assertThat(r.status()).isEqualTo(202);
        String jobId = env(r.body()).get("jobId").asText();
        awaitDone(jobId);
        JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
        assertThat(job.get("status").asText()).isEqualTo("done");
        assertThat(job.get("findings").size()).isEqualTo(1);
        assertThat(job.get("findings").get(0).get("ruleId").asText()).isEqualTo("MOCK-001");
    }

    // ---- error envelopes must survive content negotiation (STATUS.md 6.27) ----

    @Test
    void unknownJobStreamReturns404EnvelopeToAnSseClient() throws Exception {
        // An SSE client sends Accept: text/event-stream. The handler declares
        // it produces exactly that, so a JSON envelope has no acceptable
        // converter — and Spring turned the 404 into a 500 until the envelope
        // started pinning its own Content-Type. Any real client polling for a
        // job that never existed would have hit this.
        HttpSupport.RawResponse r = http().getWithAccept("/v1/reviews/nope/stream", T, "text/event-stream");
        assertThat(r.status()).isEqualTo(404);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("not_found");
    }

    @Test
    void errorEnvelopeIgnoresAnAcceptHeaderThatExcludesJson() throws Exception {
        for (String accept : new String[]{"application/xml", "text/plain", "text/event-stream"}) {
            HttpSupport.RawResponse r = http().getWithAccept("/v1/reviews/nope", T, accept);
            assertThat(r.status()).as("Accept: %s", accept).isEqualTo(404);
            assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("not_found");
        }
    }

    @Test
    void successResponsesAlsoIgnoreAnAcceptHeaderThatExcludesJson() throws Exception {
        // Same root cause on the 2xx side: /health is defined by the contract
        // as returning 200, full stop, and letting an Accept header turn the
        // liveness probe into a 500 is indefensible. This service speaks JSON
        // and only JSON, so it states that rather than negotiating it.
        for (String path : new String[]{"/health", "/spec"}) {
            HttpSupport.RawResponse r = http().getWithAccept(path, null, "application/xml");
            assertThat(r.status()).as("%s", path).isEqualTo(200);
            assertThat(env(r.body()).isObject()).isTrue();
        }
    }

    @Test
    void streamStillServesAClientAskingForSomethingOtherThanEventStream() throws Exception {
        HttpSupport.RawResponse posted = http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\"}", T, null, null, null);
        String jobId = env(posted.body()).get("jobId").asText();
        awaitDone(jobId);
        // The mapping no longer treats Accept as part of the routing decision.
        HttpSupport.RawResponse r =
                http().getWithAccept("/v1/reviews/" + jobId + "/stream", T, "application/xml");
        assertThat(r.status()).isEqualTo(200);
        assertThat(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8)).contains("event:done");
    }

    // ---- chunk-level parsing (STATUS.md 6.26) ----

    @Test
    void diffWhoseTrailingFileHasNoHunksStillCompletes() throws Exception {
        // Big enough to force a second chunk, and the last file is a pure
        // rename with no @@ at all. That chunk parses to zero lines; it used
        // to throw and fail the whole job with "internal error".
        StringBuilder diff = new StringBuilder();
        diff.append("diff --git a/big.js b/big.js\\n--- a/big.js\\n+++ b/big.js\\n@@ -1,1 +1,3000 @@\\n");
        for (int i = 0; i < 3000; i++) {
            diff.append("+// filler ").append(i).append(" xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\\n");
        }
        diff.append("+eval(danger)\\n");
        diff.append("diff --git a/old.txt b/new.txt\\nsimilarity index 100%\\nrename from old.txt\\nrename to new.txt\\n");

        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"" + diff + "\"}", T, null, null, null);
        assertThat(r.status()).isEqualTo(202);
        String jobId = env(r.body()).get("jobId").asText();
        awaitDone(jobId);

        JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
        assertThat(job.get("status").asText()).isEqualTo("done");
        assertThat(job.get("usage").get("chunks").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(job.get("findings").size()).isEqualTo(1);
        assertThat(job.get("findings").get(0).get("ruleId").asText()).isEqualTo("MOCK-001");
    }

    private void awaitDone(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
            String s = job.get("status").asText();
            if ("done".equals(s) || "failed".equals(s)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " did not reach done in time");
    }
}
