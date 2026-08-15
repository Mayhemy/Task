package com.fedjafilipovic.ai_diff_reviewer.filters;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Token bucket on POST /v1/reviews only — GETs are never rate limited.
 * Capacity 30, refill 0.5 tokens/sec (= 30/min sustained). Rejection is
 * always 429 + Retry-After + envelope, never a 5xx.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucket bucket = new TokenBucket(
            AppLimits.RATE_LIMIT_PER_MINUTE,
            AppLimits.RATE_LIMIT_PER_MINUTE / 60.0);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        boolean limited = "POST".equalsIgnoreCase(req.getMethod())
                && req.getRequestURI().equals("/v1/reviews");
        if (!limited) {
            chain.doFilter(req, res);
            return;
        }
        if (!bucket.tryAcquire()) {
            FilterJsonErrors.write(res, 429, "rate_limited",
                    "Rate limit exceeded", String.valueOf(bucket.retryAfterSeconds()));
            return;
        }
        chain.doFilter(req, res);
    }

    /** Simple synchronized token bucket. */
    static final class TokenBucket {
        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity; // start full: natural 30-request burst
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized long retryAfterSeconds() {
            refill();
            return (long) Math.ceil((1.0 - tokens) / refillPerSecond);
        }

        private void refill() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) / 1e9 * refillPerSecond);
            lastRefillNanos = now;
        }
    }
}
