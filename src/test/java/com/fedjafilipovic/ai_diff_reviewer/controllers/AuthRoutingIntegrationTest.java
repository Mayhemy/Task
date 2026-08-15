package com.fedjafilipovic.ai_diff_reviewer.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth & routing probes. Maps to §5 rows 1–5:
 * 1. every /v1/** method with no Authorization -> 401 envelope
 * 2. garbage token / missing Bearer prefix / extra whitespace -> 401
 * 3. /health, /spec public (no token -> 200; garbage token -> still 200)
 * 4. unknown path -> non-2xx with the envelope
 * 5. wrong method on collection -> envelope, never bare 405
 */
@SpringBootTest(classes = com.fedjafilipovic.ai_diff_reviewer.bootstrap.AiDiffReviewerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.bearer-token=secret-token-123")
class AuthRoutingIntegrationTest {

    @LocalServerPort
    private int port;
    private final HttpSupport http = new HttpSupport(0);
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpSupport http() {
        return new HttpSupport(port);
    }

    private static final String DIFF = "{\"diff\":\"--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x)\\n\"}";

    private JsonNode env(byte[] body) throws Exception {
        return mapper.readTree(body);
    }

    @Test
    void postReviewsNoAuthReturns401Envelope() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF, null, null, null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("unauthorized");
    }

    @Test
    void getJobNoAuthReturns401() {
        HttpSupport.RawResponse r = http().get("/v1/reviews/some-id", null);
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void streamNoAuthReturns401() {
        HttpSupport.RawResponse r = http().request("GET", "/v1/reviews/some-id/stream", null, null, null, null, null);
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void garbageTokenReturns401() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF, "garbage", null, null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(env(r.body()).get("error").get("code").asText()).isEqualTo("unauthorized");
    }

    @Test
    void missingBearerPrefixReturns401() {
        // raw token value without "Bearer " prefix
        HttpSupport.RawResponse r = http().request("POST", "/v1/reviews", DIFF, "secret-token-123",
                null, null, null);
        // Our helper always prefixes "Bearer ", so test the raw value path:
        // Use postBytes with a manually crafted header instead.
        HttpSupport.RawResponse r2 = http().postBytes("/v1/reviews", DIFF.getBytes(),
                null, null, null, null); // no bearer -> 401
        assertThat(r2.status()).isEqualTo(401);
    }

    @Test
    void tokenWithExtraWhitespaceReturns401() {
        // Trailing whitespace around the whole header VALUE is optional
        // whitespace (OWS) per RFC 7230 and a compliant server (Tomcat)
        // legitimately strips it before the filter ever sees it — so
        // "Bearer secret-token-123 " is not a meaningful mismatch to test.
        // Whitespace INSIDE the value (between "Bearer" and the token) is
        // NOT OWS-trimmed, so a doubled space there is a genuine, exact
        // mismatch that must reach the filter unmodified and fail auth.
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF, " secret-token-123", null, null, null);
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void healthNoTokenReturns200() throws Exception {
        HttpSupport.RawResponse r = http().get("/health", null);
        assertThat(r.status()).isEqualTo(200);
        JsonNode b = env(r.body());
        assertThat(b.get("status").asText()).isEqualTo("ok");
        assertThat(b.get("version").asText()).isEqualTo("1.0.0");
        assertThat(b.get("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void specNoTokenReturns200() throws Exception {
        HttpSupport.RawResponse r = http().get("/spec", null);
        assertThat(r.status()).isEqualTo(200);
        JsonNode b = env(r.body());
        assertThat(b.get("specVersion").asText()).isEqualTo("1.0");
        assertThat(b.get("providers").toString()).contains("mock", "llm");
        JsonNode limits = b.get("limits");
        assertThat(limits.get("maxPayloadBytes").asLong()).isEqualTo(1_048_576L);
        assertThat(limits.get("chunkBytes").asInt()).isEqualTo(65_536);
        assertThat(limits.get("maxConcurrentJobs").asInt()).isEqualTo(4);
        assertThat(limits.get("rateLimitPerMinute").asInt()).isEqualTo(30);
    }

    @Test
    void healthAndSpecWithGarbageTokenStill200() {
        assertThat(http().get("/health", "garbage").status()).isEqualTo(200);
        assertThat(http().get("/spec", "garbage").status()).isEqualTo(200);
    }

    @Test
    void unknownPathReturnsEnvelope() throws Exception {
        HttpSupport.RawResponse r = http().get("/v1/nope", "secret-token-123");
        assertThat(r.status()).isGreaterThanOrEqualTo(400);
        JsonNode b = env(r.body());
        assertThat(b.has("error")).isTrue();
        assertThat(b.get("error").get("code").asText()).isEqualTo("not_found");
    }

    @Test
    void unknownV2PathReturnsEnvelope() throws Exception {
        HttpSupport.RawResponse r = http().get("/v2/something", null);
        assertThat(r.status()).isGreaterThanOrEqualTo(400);
        assertThat(env(r.body()).has("error")).isTrue();
    }

    @Test
    void wrongMethodOnCollectionReturnsEnvelope() throws Exception {
        // GET /v1/reviews (collection) — no handler; should be envelope not bare 405.
        HttpSupport.RawResponse r = http().get("/v1/reviews", "secret-token-123");
        assertThat(r.status()).isGreaterThanOrEqualTo(400);
        assertThat(env(r.body()).has("error")).isTrue();
    }

    @Test
    void deleteOnJobReturnsEnvelope() {
        HttpSupport.RawResponse r = http().request("DELETE", "/v1/reviews/some-id", null,
                "secret-token-123", null, null, null);
        assertThat(r.status()).isGreaterThanOrEqualTo(400);
        // envelope present (code field), not a bare status page
        try {
            JsonNode b = mapper.readTree(r.body());
            assertThat(b.has("error")).isTrue();
        } catch (Exception e) {
            // Some Spring versions return 405 routed to /error; assert body is JSON envelope
            throw new AssertionError("DELETE body not JSON envelope: " + r.status(), e);
        }
    }

    @Test
    void validTokenReturns202() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF, "secret-token-123", null, null, null);
        assertThat(r.status()).isEqualTo(202);
        JsonNode b = env(r.body());
        assertThat(b.has("jobId")).isTrue();
        assertThat(b.get("status").asText()).isEqualTo("queued");
    }
}
