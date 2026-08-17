package com.fedjafilipovic.ai_diff_reviewer.controllers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import com.fedjafilipovic.ai_diff_reviewer.dto.ApiError;
import com.fedjafilipovic.ai_diff_reviewer.models.Job;
import com.fedjafilipovic.ai_diff_reviewer.dto.ReviewOptions;
import com.fedjafilipovic.ai_diff_reviewer.services.JobService;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.InvalidJsonException;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the request body manually (bounded) and parses it with readTree —
 * this gives exact control over 413/400/422 precedence and means we never
 * emit a non-envelope 415 for an unexpected Content-Type.
 */
@RestController
public class ReviewController {

    private final JobService jobService;
    private final ObjectMapper mapper;

    public ReviewController(JobService jobService, ObjectMapper mapper) {
        this.jobService = jobService;
        this.mapper = mapper;
    }

    @PostMapping("/v1/reviews")
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request) throws IOException {
        byte[] rawBody = readBounded(request);
        JsonNode root = parseJson(rawBody);
        // options (400 invalid_json) is validated before diff (422 invalid_diff)
        // to honor the documented precedence 400 -> 422 when a request has both
        // an invalid diff and an invalid options value.
        ReviewOptions options = extractOptions(root);
        String diff = extractDiff(root);

        String idempotencyKey = request.getHeader("Idempotency-Key");
        JobService.SubmitOutcome outcome = jobService.submit(rawBody, idempotencyKey, diff, options);

        Job job = outcome.job();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.getId());
        body.put("status", job.getStatus().toJson());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @GetMapping("/v1/reviews/{jobId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.getId());
        body.put("status", job.getStatus().toJson());
        body.put("findings", job.getFindings());
        body.put("usage", job.getUsage());
        if (job.getErrorMessage() != null) {
            // ErrorEnvelope's inner record, not a Map.of — a record serializes
            // in declaration order, where Map.of reshuffles per JVM start.
            body.put("error", new ApiError("internal", job.getErrorMessage()));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    // ---- body reading & validation ----

    /**
     * Fast-path Content-Length check, then a bounded read so a lying/absent
     * header (chunked encoding) can't bypass the 413.
     */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > AppLimits.MAX_PAYLOAD_BYTES) {
            drainWhatIsAlreadyComing(request);
            throw new PayloadTooLargeException();
        }
        InputStream in = request.getInputStream();
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > AppLimits.MAX_PAYLOAD_BYTES) {
                drainWhatIsAlreadyComing(request);
                throw new PayloadTooLargeException();
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static final long MAX_DRAIN_BYTES = 8L * 1024 * 1024;
    /** Give up once the client has sent nothing at all for this long. */
    private static final long DRAIN_IDLE_GIVEUP_MILLIS = 2_000;
    /** Absolute ceiling, however fast the client keeps sending. */
    private static final long DRAIN_TOTAL_BUDGET_MILLIS = 12_000;

    /**
     * Reads and discards whatever the client is still sending, so the upload
     * can finish before we close the connection under it.
     *
     * Why: answering 413 the moment the limit trips is correct HTTP, and
     * against the origin directly every client sees it. But it commits the
     * response mid-upload, and an intermediary relaying into an upstream that
     * has stopped reading turns that into a TCP reset. Clients that read while
     * writing (curl) still get the 413; clients that write the whole body
     * before reading (python-requests, urllib) get a ConnectionError and never
     * see the status we sent. Measured through the tunnel: 1 MiB accepted
     * cleanly, 1 MiB + 1 byte reset. Letting the bytes land removes the cause.
     *
     * Why it cannot block: it only reads what {@code available()} already has
     * buffered, so no read ever waits on bytes that may never arrive. A client
     * that lies with a huge Content-Length and then sends nothing is exactly
     * what the fast path above rejects cheaply, and it must not be able to park
     * a request thread — it costs {@link #DRAIN_IDLE_GIVEUP_MILLIS} and nothing
     * more. (For scale: a client can already hold a thread for Tomcat's 20 s
     * connectionTimeout just by trickling a legitimate body, so this widens
     * nothing that was not already open.)
     *
     * Bounded three ways because no single bound is enough: idle time handles
     * the liar, the total budget handles a slow trickle, and the byte cap
     * handles someone genuinely sending gigabytes. The idle window is 2 s
     * rather than something tighter because a large upload through a tunnel
     * really does pause — a 2 MiB body stalled long enough to trip a 500 ms
     * window and get reset again.
     */
    private static void drainWhatIsAlreadyComing(HttpServletRequest request) {
        long hardDeadline = System.currentTimeMillis() + DRAIN_TOTAL_BUDGET_MILLIS;
        long idleDeadline = System.currentTimeMillis() + DRAIN_IDLE_GIVEUP_MILLIS;
        long discarded = 0;
        try {
            InputStream in = request.getInputStream();
            byte[] sink = new byte[8192];
            while (System.currentTimeMillis() < hardDeadline
                    && System.currentTimeMillis() < idleDeadline
                    && discarded < MAX_DRAIN_BYTES) {
                int available = in.available();
                if (available <= 0) {
                    Thread.sleep(10);
                    continue;
                }
                int n = in.read(sink, 0, Math.min(sink.length, available));
                if (n < 0) {
                    break; // client finished; nothing left to swallow
                }
                discarded += n;
                idleDeadline = System.currentTimeMillis() + DRAIN_IDLE_GIVEUP_MILLIS;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Client went away mid-upload. We are rejecting the request anyway.
        }
    }

    private JsonNode parseJson(byte[] rawBody) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw new InvalidJsonException("Malformed JSON body");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidJsonException("Body must be a JSON object");
        }
        return root;
    }

    /**
     * diff missing / empty / blank / non-textual -> 422 invalid_diff
     * (a non-string is "not parseable as a unified diff").
     */
    private String extractDiff(JsonNode root) {
        JsonNode diffNode = root.get("diff");
        if (diffNode == null || diffNode.isNull()) {
            throw new com.fedjafilipovic.ai_diff_reviewer.exceptions.InvalidDiffException("diff is required");
        }
        if (!diffNode.isTextual()) {
            throw new com.fedjafilipovic.ai_diff_reviewer.exceptions.InvalidDiffException("diff must be a string");
        }
        String diff = diffNode.asText();
        if (diff.isBlank()) {
            throw new com.fedjafilipovic.ai_diff_reviewer.exceptions.InvalidDiffException("diff must not be empty");
        }
        return diff;
    }

    private ReviewOptions extractOptions(JsonNode root) {
        JsonNode options = root.get("options");
        if (options == null || options.isNull()) {
            return ReviewOptions.defaults();
        }
        // Present but the wrong type (string/number/boolean/array) is a
        // malformed request, not "absent" — reject it the same way a
        // non-textual `diff` is rejected, rather than silently defaulting.
        if (!options.isObject()) {
            throw new InvalidJsonException("options must be an object");
        }
        String provider = ReviewOptions.PROVIDER_MOCK;
        JsonNode providerNode = options.get("provider");
        if (providerNode != null && !providerNode.isNull()) {
            if (!providerNode.isTextual()) {
                throw new InvalidJsonException("options.provider must be 'mock' or 'llm'");
            }
            provider = providerNode.asText();
            if (!provider.equals(ReviewOptions.PROVIDER_MOCK) && !provider.equals(ReviewOptions.PROVIDER_LLM)) {
                throw new InvalidJsonException("options.provider must be 'mock' or 'llm'");
            }
        }
        int maxFindings = AppLimits.DEFAULT_MAX_FINDINGS;
        JsonNode maxNode = options.get("maxFindings");
        if (maxNode != null && !maxNode.isNull()) {
            if (!maxNode.isInt() && !maxNode.isLong()) {
                throw new InvalidJsonException("options.maxFindings must be an integer");
            }
            // A value that fits in a long but overflows int (e.g. 3000000000)
            // is still isLong()==true — asInt() on that throws internally
            // instead of clamping/wrapping, which previously escaped as an
            // uncaught 500. Check the range explicitly first.
            long asLong = maxNode.asLong();
            if (asLong > Integer.MAX_VALUE || asLong < Integer.MIN_VALUE) {
                throw new InvalidJsonException("options.maxFindings is out of range");
            }
            maxFindings = (int) asLong;
            // The spec states a default (100) but never a minimum: 0 is a
            // legitimate instruction to report no findings while still
            // running the full scan (usage stays accurate) — only a
            // negative count is actually malformed input.
            if (maxFindings < 0) {
                throw new InvalidJsonException("options.maxFindings must be a non-negative integer");
            }
        }
        return new ReviewOptions(provider, maxFindings);
    }
}
