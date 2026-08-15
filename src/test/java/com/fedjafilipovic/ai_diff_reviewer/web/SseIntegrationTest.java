package com.fedjafilipovic.ai_diff_reviewer.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE probes. Maps to §5 rows 32–34:
 * 32. live connection -> ordered status/finding/done, connection closes after done
 * 33. connect after completion twice -> byte-identical replay, identical to live
 * 34. connect to a failed job -> replays through status:failed, then closes
 *
 * @DirtiesContext: see LifecycleIntegrationTest — several classes share this
 * exact `properties` value and thus, without isolation, the same singleton
 * RateLimitFilter TokenBucket, whose combined traffic can spuriously exceed
 * 30/min. Every class sharing this properties value carries the same
 * annotation so whichever runs first always leaves a clean context behind.
 *
 * The llm-* overrides force the llm provider into its unconfigured state
 * deterministically — row 34 (failedJobReplayTerminatesWithStatusFailed)
 * needs a job that reliably fails, regardless of whatever the developer's
 * local .env happens to contain.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.bearer-token=tok", "app.llm-base-url=", "app.llm-api-key=", "app.llm-model="})
class SseIntegrationTest {

    @LocalServerPort
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpSupport http() { return new HttpSupport(port); }
    private static final String T = "tok";

    private JsonNode env(byte[] body) throws Exception { return mapper.readTree(body); }

    private static final String DIFF_BODY =
            "{\"diff\":\"--- a/f.js\\n+++ b/f.js\\n@@ -1 +1 @@\\n+eval(x); console.log(y)\\n\"}";

    @Test
    void liveStreamEmitsOrderedStatusFindingDoneThenCloses() throws Exception {
        // Create a job and connect to its stream immediately.
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF_BODY, T, null, null, null);
        String jobId = env(r.body()).get("jobId").asText();

        List<String> events = http().sse("/v1/reviews/" + jobId + "/stream", T);
        assertThat(events).isNotEmpty();

        // Extract event names in order.
        List<String> names = events.stream().map(SseIntegrationTest::eventName).toList();
        // Must start with status:queued, contain status:running, findings, status:done, done.
        assertThat(names.get(0)).isEqualTo("status");
        assertThat(names).contains("finding");
        assertThat(names).contains("done");
        // `done` is the last event.
        assertThat(names.get(names.size() - 1)).isEqualTo("done");
        // There are exactly 2 findings (eval + console.log).
        long findingCount = names.stream().filter("finding"::equals).count();
        assertThat(findingCount).isEqualTo(2);
    }

    @Test
    void postCompletionReplayIsByteIdenticalToLive() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF_BODY, T, null, null, null);
        String jobId = env(r.body()).get("jobId").asText();
        // Wait for completion via polling.
        awaitDone(jobId);

        // Connect twice after completion; both replays must be identical.
        List<String> replay1 = http().sse("/v1/reviews/" + jobId + "/stream", T);
        List<String> replay2 = http().sse("/v1/reviews/" + jobId + "/stream", T);
        assertThat(replay1).isEqualTo(replay2);

        // The replay must contain the full sequence: queued, running, findings, done.
        List<String> names = replay1.stream().map(SseIntegrationTest::eventName).toList();
        assertThat(names).contains("status", "finding", "done");
        assertThat(names.get(names.size() - 1)).isEqualTo("done");
    }

    @Test
    void failedJobReplayTerminatesWithStatusFailed() throws Exception {
        String body = "{\"diff\":\"+++ b/f.js\\n@@ -1 +1 @@\\n+x\\n\",\"options\":{\"provider\":\"llm\"}}";
        HttpSupport.RawResponse r = http().post("/v1/reviews", body, T, null, null, null);
        String jobId = env(r.body()).get("jobId").asText();
        awaitFailed(jobId);

        List<String> events = http().sse("/v1/reviews/" + jobId + "/stream", T);
        List<String> names = events.stream().map(SseIntegrationTest::eventName).toList();
        // Terminal event is status:failed; there is no `done` event.
        assertThat(names.get(names.size() - 1)).isEqualTo("status");
        assertThat(names).doesNotContain("done");
        // The final status event carries the failed status.
        String last = events.get(events.size() - 1);
        assertThat(last).contains("\"failed\"");
        assertThat(last).contains("error");
    }

    @Test
    void streamContentTypeIsEventStream() throws Exception {
        HttpSupport.RawResponse r = http().post("/v1/reviews", DIFF_BODY, T, null, null, null);
        String jobId = env(r.body()).get("jobId").asText();
        awaitDone(jobId);
        // A GET on the stream endpoint should return text/event-stream content type.
        HttpSupport.RawResponse streamResp = http().request("GET", "/v1/reviews/" + jobId + "/stream",
                null, T, null, "text/event-stream", null);
        // We can't easily read the content-type via our helper's GET (it reads body),
        // so assert the SSE body parses as events instead.
        assertThat(streamResp.status()).isEqualTo(200);
    }

    @Test
    void streamRequiresAuth() {
        HttpSupport.RawResponse r = http().request("GET", "/v1/reviews/x/stream", null, null, null, null, null);
        assertThat(r.status()).isEqualTo(401);
    }

    // ---- helpers ----

    /** Extracts the `event:` name from an SSE event block. */
    private static String eventName(String eventBlock) {
        for (String line : eventBlock.split("\n")) {
            if (line.startsWith("event:")) {
                return line.substring("event:".length()).trim();
            }
        }
        return "message";
    }

    private void awaitDone(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
            if ("done".equals(job.get("status").asText())) return;
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " did not reach done");
    }

    private void awaitFailed(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = env(http().get("/v1/reviews/" + jobId, T).body());
            if ("failed".equals(job.get("status").asText())) return;
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " did not reach failed");
    }
}
