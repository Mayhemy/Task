package com.fedjafilipovic.ai_diff_reviewer.web;

import com.fedjafilipovic.ai_diff_reviewer.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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
        if (header == null || !header.equals(expected)) {
            FilterJsonErrors.write(res, 401, "unauthorized", "Missing or invalid bearer token");
            return;
        }
        chain.doFilter(req, res);
    }
}
