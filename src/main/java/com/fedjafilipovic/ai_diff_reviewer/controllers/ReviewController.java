package com.fedjafilipovic.ai_diff_reviewer.controllers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import com.fedjafilipovic.ai_diff_reviewer.models.Job;
import com.fedjafilipovic.ai_diff_reviewer.dto.ReviewOptions;
import com.fedjafilipovic.ai_diff_reviewer.services.JobService;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.InvalidJsonException;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
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
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
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
            body.put("error", Map.of("code", "internal", "message", job.getErrorMessage()));
        }
        return ResponseEntity.ok(body);
    }

    // ---- body reading & validation ----

    /**
     * Fast-path Content-Length check, then a bounded read so a lying/absent
     * header (chunked encoding) can't bypass the 413.
     */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > AppLimits.MAX_PAYLOAD_BYTES) {
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
                throw new PayloadTooLargeException();
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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
