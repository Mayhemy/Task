package com.fedjafilipovic.ai_diff_reviewer.configuration;

import org.springframework.stereotype.Component;

import java.time.Instant;

/** Captures startup time so /health can report uptimeSeconds. */
@Component
public class UptimeClock {

    private final Instant startedAt = Instant.now();

    public long uptimeSeconds() {
        return java.time.Duration.between(startedAt, Instant.now()).getSeconds();
    }
}
