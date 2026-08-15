package com.fedjafilipovic.ai_diff_reviewer.web;

import com.fedjafilipovic.ai_diff_reviewer.config.AppLimits;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Public self-declaration of limits and providers. Values come from AppLimits. */
@RestController
public class SpecController {

    @GetMapping("/spec")
    public Map<String, Object> spec() {
        return Map.of(
                "specVersion", AppLimits.SPEC_VERSION,
                "providers", List.of("mock", "llm"),
                "limits", Map.of(
                        "maxPayloadBytes", AppLimits.MAX_PAYLOAD_BYTES,
                        "chunkBytes", AppLimits.CHUNK_BYTES,
                        "maxConcurrentJobs", AppLimits.MAX_CONCURRENT_JOBS,
                        "rateLimitPerMinute", AppLimits.RATE_LIMIT_PER_MINUTE));
    }
}
