package com.fedjafilipovic.ai_diff_reviewer.filters;

import com.fedjafilipovic.ai_diff_reviewer.configuration.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Outermost filter: every method on /v1/** requires the exact bearer token.
 * /health and /spec stay public.
 */
@Component
@Order(1)
public class BearerAuthFilter extends OncePerRequestFilter {

    private final AppProperties props;

    public BearerAuthFilter(AppProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String uri = req.getRequestURI();
        boolean protectedPath = uri.equals("/v1") || uri.startsWith("/v1/");
        if (!protectedPath) {
            chain.doFilter(req, res);
            return;
        }
        String header = req.getHeader("Authorization");
        String expected = "Bearer " + props.getBearerToken();
        if (header == null || !constantTimeEquals(header, expected)) {
            res.setHeader("WWW-Authenticate", "Bearer");
            FilterJsonErrors.write(res, 401, "unauthorized", "Missing or invalid bearer token");
            return;
        }
        chain.doFilter(req, res);
    }

    /**
     * MessageDigest.isEqual runs in constant time regardless of where the
     * first mismatching byte falls, so a wrong-token guess can't be narrowed
     * down by measuring response latency the way a short-circuiting
     * String.equals comparison theoretically could.
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
