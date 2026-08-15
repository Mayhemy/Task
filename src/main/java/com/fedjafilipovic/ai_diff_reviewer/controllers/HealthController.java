package com.fedjafilipovic.ai_diff_reviewer.controllers;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import com.fedjafilipovic.ai_diff_reviewer.configuration.UptimeClock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public (unauthenticated) liveness probe. */
@RestController
public class HealthController {

    private final UptimeClock uptime;

    public HealthController(UptimeClock uptime) {
        this.uptime = uptime;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "version", AppLimits.VERSION,
                "uptimeSeconds", uptime.uptimeSeconds());
    }
}
