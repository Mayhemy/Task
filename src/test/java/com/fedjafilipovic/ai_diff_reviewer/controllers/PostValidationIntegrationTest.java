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
 * POST validation probes. Maps to §5 rows 6–11:
 * 6. payload boundary exactly 1 MiB passes / +1 byte 413 (with and without truthful Content-Length)
 * 7. malformed JSON / wrong shape -> 400 invalid_json
 * 8. diff missing/empty/blank/non-textual/no-hunks -> 422 invalid_diff
 * 9. unknown top-level fields ignored -> 202
 * 10. options defaults / invalid provider / maxFindings 0 -> 400; truncation
 * 11. Content-Type text/plain with valid JSON -> works (no 415)
 *
 * Precedence enforced by filter/controller ordering: 401 -> 429 -> 413 -> 400 -> 422.
 *
 * @DirtiesContext: see LifecycleIntegrationTest — several classes share this
 * exact `properties` value and thus, without isolation, the same singleton
 * RateLimitFilter TokenBucket, whose combined traffic can spuriously exceed
 * 30/min (this class alone sends ~25 POSTs). Every class sharing this
 * properties value carries the same annotation so whichever runs first
 * always leaves a clean context behind.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = com.fedjafilipovic.ai_diff_reviewer.bootstrap.AiDiffReviewerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.bearer-token=tok")
class PostValidationIntegrationTest {

    @LocalServerPort
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpSupport http() { return new HttpSupport(port); }
    private static final String T = "tok";

    private static final String VALID_DIFF =
            "--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x)\\n";

    private JsonNode env(byte[] body) throws Exception { return mapper.readTree(body); }

    // ---- row 6: payload boundary ----

    /**
     * Builds a JSON body {"diff":"<diffValue>"} whose total UTF-8 size is
     * exactly {@code targetBytes}. The diff value is a valid single-hunk diff
     * plus a padding added line of 'a' characters (1 byte each, no escaping).
     */
    private static byte[] bodyOfExactly(int targetBytes) {
        String prefix = "{\"diff\":\"--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+x\\n+";
        String suffix = "\\n\"}";
        int overhead = (prefix + suffix).getBytes(StandardCharsets.UTF_8).length;
        int padLen = targetBytes - overhead;
        assertThat(padLen).isGreaterThan(0);
        String body = prefix + "a".repeat(padLen) + suffix;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isEqualTo(targetBytes);
        return bytes;
    }

    @Test
    void bodyOfExactlyOneMebibyteIsAccepted() throws Exception {
        byte[] body = bodyOfExactly(1_048_576);
        HttpSupport.RawResponse r = http().postBytes("/v1/reviews", body, T, null, null, null);
        assertThat(r.status()).isEqualTo(202);
    }

    @Test
    void bodyOneByteOverOneMebibyteReturns413() throws Exception {
        byte[] body = bodyOfExactly(1_048_577);
        HttpSupport.RawResponse r = http().postBytes("/v1/reviews", body, T, null, null, null);
        assertThat(r.status()).isEqualTo(413);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("payload_too_large");
    }

    @Test
    void oversizedDeclaredContentLengthReturns413() throws Exception {
        // Lie with a large Content-Length via a raw socket (HttpURLConnection
        // silently recomputes Content-Length from the actual bytes written,
        // so it can't be used to send a truly lying header). The fast-path
        // check must reject based on the declared header alone, without
        // waiting to read a body that never fully arrives.
        String statusLine = http().postWithLyingContentLength(
                "/v1/reviews", T, "{\"diff\":\"x\"}", 2_000_000);
        assertThat(statusLine).contains("413");
    }

    // ---- row 7: malformed JSON / wrong shape ----

    @Test
    void malformedJsonReturns400() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", "{\"diff\": ", T, null, null, null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("invalid_json");
    }

    @Test
    void jsonArrayShapeReturns400() throws Exception {
        assertThat(http().post("/v1/reviews", "[1,2]", T, null, null, null).status()).isEqualTo(400);
    }

    @Test
    void jsonStringShapeReturns400() {
        assertThat(http().post("/v1/reviews", "\"hello\"", T, null, null, null).status()).isEqualTo(400);
    }

    @Test
    void jsonNumberShapeReturns400() {
        assertThat(http().post("/v1/reviews", "42", T, null, null, null).status()).isEqualTo(400);
    }

    // ---- row 8: diff field validation ----

    @Test
    void diffMissingReturns422() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", "{}", T, null, null, null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("invalid_diff");
    }

    @Test
    void diffEmptyReturns422() {
        assertThat(http().post("/v1/reviews", "{\"diff\":\"\"}", T, null, null, null).status()).isEqualTo(422);
    }

    @Test
    void diffBlankReturns422() {
        assertThat(http().post("/v1/reviews", "{\"diff\":\"   \"}", T, null, null, null).status()).isEqualTo(422);
    }

    @Test
    void diffNonTextualReturns422() {
        assertThat(http().post("/v1/reviews", "{\"diff\":123}", T, null, null, null).status()).isEqualTo(422);
        assertThat(http().post("/v1/reviews", "{\"diff\":{}}", T, null, null, null).status()).isEqualTo(422);
    }

    @Test
    void diffNoHunksReturns422() {
        // valid JSON string but not a unified diff (no @@ hunks)
        assertThat(http().post("/v1/reviews", "{\"diff\":\"just some text\"}", T, null, null, null).status()).isEqualTo(422);
    }

    // ---- row 9: unknown fields ignored ----

    @Test
    void unknownTopLevelFieldsIgnored() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\",\"extra\":1}", T, null, null, null);
        assertThat(r.status()).isEqualTo(202);
    }

    // ---- row 10: options ----

    @Test
    void optionsWrongTypeReturns400() {
        // options present but not an object (string/number/boolean/array) is
        // malformed, not "absent" — must be rejected like a non-textual diff
        // is, not silently defaulted.
        assertThat(http().post("/v1/reviews", "{\"diff\":\"" + VALID_DIFF + "\",\"options\":\"nope\"}", T, null, null, null).status()).isEqualTo(400);
        assertThat(http().post("/v1/reviews", "{\"diff\":\"" + VALID_DIFF + "\",\"options\":42}", T, null, null, null).status()).isEqualTo(400);
        assertThat(http().post("/v1/reviews", "{\"diff\":\"" + VALID_DIFF + "\",\"options\":true}", T, null, null, null).status()).isEqualTo(400);
        assertThat(http().post("/v1/reviews", "{\"diff\":\"" + VALID_DIFF + "\",\"options\":[1,2]}", T, null, null, null).status()).isEqualTo(400);
    }

    @Test
    void optionsAbsentUsesDefaults() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", "{\"diff\":\"" + VALID_DIFF + "\"}", T, null, null, null);
        assertThat(r.status()).isEqualTo(202);
        String jobId = env(r.body()).get("jobId").asText();
        awaitDone(jobId);
        JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
        assertThat(job.get("status").asText()).isEqualTo("done");
        assertThat(job.get("findings").size()).isGreaterThan(0);
    }

    @Test
    void optionsNullUsesDefaults() {
        assertThat(http().post("/v1/reviews", "{\"diff\":\"" + VALID_DIFF + "\",\"options\":null}", T, null, null, null).status()).isEqualTo(202);
    }

    @Test
    void optionsEmptyObjectUsesDefaults() {
        assertThat(http().post("/v1/reviews", "{\"diff\":\"" + VALID_DIFF + "\",\"options\":{}}", T, null, null, null).status()).isEqualTo(202);
    }

    @Test
    void invalidProviderReturns400() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\",\"options\":{\"provider\":\"foo\"}}", T, null, null, null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("invalid_json");
    }

    @Test
    void providerCaseSensitiveMockCapitalReturns400() {
        // "Mock" != "mock"
        assertThat(http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\",\"options\":{\"provider\":\"Mock\"}}", T, null, null, null).status()).isEqualTo(400);
    }

    @Test
    void maxFindingsZeroReturns400() {
        assertThat(http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\",\"options\":{\"maxFindings\":0}}", T, null, null, null).status()).isEqualTo(400);
    }

    @Test
    void maxFindingsNegativeReturns400() {
        assertThat(http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\",\"options\":{\"maxFindings\":-5}}", T, null, null, null).status()).isEqualTo(400);
    }

    @Test
    void maxFindingsOverflowingIntReturns400NotInternalError() throws Exception {
        // A value that fits in a JSON/long number but overflows a 32-bit int
        // (Integer.MAX_VALUE = 2147483647) must be rejected as invalid input,
        // not crash: JsonNode.asInt() throws on out-of-range long values
        // rather than clamping, which previously escaped as an uncaught 500.
        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\",\"options\":{\"maxFindings\":3000000000}}", T, null, null, null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("invalid_json");
    }

    @Test
    void maxFindingsTruncatesFindingsButUsageReflectsFullScan() throws Exception {
        // A diff with 3 findings, maxFindings=3 -> exactly 3 returned.
        String diff = "--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+eval(a); console.log(b); // TODO\\n";
        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"" + diff + "\",\"options\":{\"maxFindings\":3}}", T, null, null, null);
        String jobId = env(r.body()).get("jobId").asText();
        awaitDone(jobId);
        JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
        assertThat(job.get("findings").size()).isEqualTo(3);
        JsonNode usage = job.get("usage");
        assertThat(usage.get("inputBytes").asLong())
                .isEqualTo(("--- a/f.js\n+++ b/f.js\n@@ -1 +1 @@\n+eval(a); console.log(b); // TODO\n")
                        .getBytes(StandardCharsets.UTF_8).length);
    }

    // ---- row 11: Content-Type text/plain with valid JSON ----

    @Test
    void textPlainWithValidJsonWorks() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"" + VALID_DIFF + "\"}", T, null, "text/plain", null);
        assertThat(r.status()).isEqualTo(202);
    }

    // ---- precedence: 401 before 429 before 413 before 400 before 422 ----

    @Test
    void authPrecedesRateLimitAndValidation() throws Exception {
        // No auth + huge body + bad json -> still 401 (auth is outermost).
        HttpSupport.RawResponse r = http().post("/v1/reviews", "garbage", null, null, null, null);
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void invalidJsonPrecedesInvalidDiff() throws Exception {
        // Malformed JSON (400) should win over a diff that would be 422.
        HttpSupport.RawResponse r = http().post("/v1/reviews", "{bad", T, null, null, null);
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void invalidOptionsPrecedesInvalidDiff() throws Exception {
        // A request with BOTH an invalid diff (would be 422) and an invalid
        // options.provider (would be 400) must return 400, per the documented
        // precedence 400 -> 422.
        HttpSupport.RawResponse r = http().post("/v1/reviews",
                "{\"diff\":\"\",\"options\":{\"provider\":\"foo\"}}", T, null, null, null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("invalid_json");
    }

    // ---- helpers ----

    private void awaitDone(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
            String s = job.get("status").asText();
            if ("done".equals(s) || "failed".equals(s)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " did not finish in time");
    }
}
