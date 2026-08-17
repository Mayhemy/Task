package com.fedjafilipovic.ai_diff_reviewer.controllers;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public self-declaration of limits and providers. Values come from AppLimits. */
@RestController
public class SpecController {

    /** LinkedHashMap, not Map.of — see HealthController for why. */
    @GetMapping("/spec")
    public ResponseEntity<Map<String, Object>> spec() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxPayloadBytes", AppLimits.MAX_PAYLOAD_BYTES);
        limits.put("chunkBytes", AppLimits.CHUNK_BYTES);
        limits.put("maxConcurrentJobs", AppLimits.MAX_CONCURRENT_JOBS);
        limits.put("rateLimitPerMinute", AppLimits.RATE_LIMIT_PER_MINUTE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("specVersion", AppLimits.SPEC_VERSION);
        body.put("providers", List.of("mock", "llm"));
        body.put("limits", limits);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
