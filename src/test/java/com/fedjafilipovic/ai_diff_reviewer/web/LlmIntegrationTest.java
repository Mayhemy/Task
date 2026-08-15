package com.fedjafilipovic.ai_diff_reviewer.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM provider probe. Maps to §5 row 31:
 * provider:"llm" with blank credentials -> 202, then failed with
 * "llm provider not configured", no 5xx, stream terminates cleanly.
 *
 * (The configured-success path needs real credentials and is run manually.)
 *
 * The property overrides below MUST use the real bound path (app.llm-base-url,
 * kebab-case, flat under the "app" prefix — matching application.properties'
 * `app.llm-base-url=${LLM_BASE_URL:}` mapping) rather than a nested
 * "app.llm.base-url" path, which silently does not bind to anything. Getting
 * this wrong means these tests pass or fail based on whatever the developer's
 * own .env file happens to contain instead of deterministically forcing the
 * unconfigured state under test — exactly the coupling this override exists
 * to prevent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.bearer-token=tok",
                "app.llm-base-url=",
                "app.llm-api-key=",
                "app.llm-model="
        })
class LlmIntegrationTest {

    @LocalServerPort
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpSupport http() { return new HttpSupport(port); }
    private static final String T = "tok";

    private JsonNode env(byte[] body) throws Exception { return mapper.readTree(body); }

    @Test
    void unconfiguredLlmProviderFailsCleanly() throws Exception {
        String body = "{\"diff\":\"+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x)\\n\",\"options\":{\"provider\":\"llm\"}}";
        HttpSupport.RawResponse r = http().post("/v1/reviews", body, T, null, null, null);
        // POST still returns 202 (async).
        assertThat(r.status()).isEqualTo(202);
        String jobId = env(r.body()).get("jobId").asText();

        // Poll until terminal.
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode job = null;
        while (System.currentTimeMillis() < deadline) {
            job = env(http().get("/v1/reviews/" + jobId, T).body());
            String s = job.get("status").asText();
            if ("done".equals(s) || "failed".equals(s)) break;
            Thread.sleep(50);
        }
        assertThat(job).isNotNull();
        assertThat(job.get("status").asText()).isEqualTo("failed");
        assertThat(job.get("error").get("message").asText()).contains("not configured");
        // GET returns 200 (failure is a job outcome).
        assertThat(http().get("/v1/reviews/" + jobId, T).status()).isEqualTo(200);
    }

    @Test
    void unconfiguredLlmStreamTerminatesCleanly() throws Exception {
        String body = "{\"diff\":\"+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x)\\n\",\"options\":{\"provider\":\"llm\"}}";
        HttpSupport.RawResponse r = http().post("/v1/reviews", body, T, null, null, null);
        String jobId = env(r.body()).get("jobId").asText();
        // Wait for terminal.
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
            if ("failed".equals(job.get("status").asText())) break;
            Thread.sleep(50);
        }
        // Stream replays and terminates (no hang).
        java.util.List<String> events = http().sse("/v1/reviews/" + jobId + "/stream", T);
        assertThat(events).isNotEmpty();
        String last = events.get(events.size() - 1);
        assertThat(last).contains("\"failed\"");
    }
}
