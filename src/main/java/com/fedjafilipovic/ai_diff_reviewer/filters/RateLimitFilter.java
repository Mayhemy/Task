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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket on POST /v1/reviews only — GETs are never rate limited.
 * Two layers: a per-caller bucket (keyed by the Authorization header, so a
 * single-tenant deployment with one token behaves exactly like one shared
 * bucket) at capacity {@link AppLimits#RATE_LIMIT_PER_MINUTE} — this is the
 * limit /spec declares — plus one overall bucket at
 * {@link AppLimits#RATE_LIMIT_HARD_CAP_PER_MINUTE} shared across every
 * caller as a guardrail. A request must clear both to proceed; either one
 * being empty is a 429 + Retry-After + envelope, never a 5xx.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, TokenBucket> perCaller = new ConcurrentHashMap<>();
    private final TokenBucket overall = new TokenBucket(
            AppLimits.RATE_LIMIT_HARD_CAP_PER_MINUTE,
            AppLimits.RATE_LIMIT_HARD_CAP_PER_MINUTE / 60.0);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        boolean limited = "POST".equalsIgnoreCase(req.getMethod())
                && req.getRequestURI().equals("/v1/reviews");
        if (!limited) {
            chain.doFilter(req, res);
            return;
        }

        String callerKey = callerKeyOf(req);
        TokenBucket bucket = perCaller.computeIfAbsent(callerKey, k -> new TokenBucket(
                AppLimits.RATE_LIMIT_PER_MINUTE, AppLimits.RATE_LIMIT_PER_MINUTE / 60.0));

        if (!bucket.tryAcquire()) {
            reject(res, bucket);
            return;
        }
        if (!overall.tryAcquire()) {
            bucket.refund(); // don't let a global-cap rejection cost this caller a token
            reject(res, overall);
            return;
        }
        chain.doFilter(req, res);
    }

    private static void reject(HttpServletResponse res, TokenBucket exhausted) throws IOException {
        FilterJsonErrors.write(res, 429, "rate_limited",
                "Rate limit exceeded", String.valueOf(exhausted.retryAfterSeconds()));
    }

    /** By this point BearerAuthFilter has already validated the token, so the
     *  header value alone is a stable, sufficient per-caller identity. */
    private static String callerKeyOf(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        return header != null ? header : "unauthenticated";
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
            this.tokens = capacity; // start full: natural burst up to capacity
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

        /** Gives back a token taken by a caller whose request was rejected by
         *  a different, downstream check — that request never actually went through. */
        synchronized void refund() {
            tokens = Math.min(capacity, tokens + 1.0);
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
