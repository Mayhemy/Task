package com.fedjafilipovic.ai_diff_reviewer.controllers;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import com.fedjafilipovic.ai_diff_reviewer.configuration.UptimeClock;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public (unauthenticated) liveness probe.
 *
 * The Content-Type is stated rather than negotiated — see
 * {@link com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptionHandler}
 * for why every response in this service does that.
 */
@RestController
public class HealthController {

    private final UptimeClock uptime;

    public HealthController(UptimeClock uptime) {
        this.uptime = uptime;
    }

    /**
     * LinkedHashMap, not Map.of: Map.of's iteration order is derived from a
     * SALT randomized once per JVM start, so Jackson emitted these three
     * fields in a different order after every restart. Harmless to a JSON
     * parser, but the contract prints them in one order and a response that
     * silently reshuffles itself on restart is not something to leave in.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", AppLimits.VERSION);
        body.put("uptimeSeconds", uptime.uptimeSeconds());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
