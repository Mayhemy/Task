package com.fedjafilipovic.ai_diff_reviewer.controllers;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import com.fedjafilipovic.ai_diff_reviewer.configuration.UptimeClock;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status", "ok",
                        "version", AppLimits.VERSION,
                        "uptimeSeconds", uptime.uptimeSeconds()));
    }
}
