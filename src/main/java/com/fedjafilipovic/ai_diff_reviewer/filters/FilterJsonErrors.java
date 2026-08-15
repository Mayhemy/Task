package com.fedjafilipovic.ai_diff_reviewer.filters;

import com.fedjafilipovic.ai_diff_reviewer.dto.ErrorEnvelope;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Writes the error envelope directly from a filter (before the dispatcher). */
final class FilterJsonErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FilterJsonErrors() {}

    static void write(HttpServletResponse res, int status, String code, String message) throws IOException {
        write(res, status, code, message, null);
    }

    static void write(HttpServletResponse res, int status, String code, String message,
                      String retryAfter) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        if (retryAfter != null) {
            res.setHeader("Retry-After", retryAfter);
        }
        res.getWriter().write(MAPPER.writeValueAsString(ErrorEnvelope.of(code, message)));
    }
}
