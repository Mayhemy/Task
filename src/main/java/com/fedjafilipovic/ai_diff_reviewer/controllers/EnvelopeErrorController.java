package com.fedjafilipovic.ai_diff_reviewer.controllers;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

/**
 * The contract says the error envelope applies to ALL non-2xx. Spring's own
 * errors (404 unknown route, 405 wrong method, 415 wrong content type) route
 * to /error — so this controller renders them as the envelope too, never the
 * default whitelabel JSON.
 */
@Controller
public class EnvelopeErrorController implements ErrorController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RequestMapping("/error")
    public ResponseEntity<String> error(HttpServletRequest req) throws Exception {
        Object statusObj = req.getAttribute("jakarta.servlet.error.status_code");
        int status = statusObj instanceof Integer i ? i : 500;

        String code;
        String message;
        if (status == 404) {
            code = "not_found";
            message = "No such resource";
        } else if (status == 405) {
            code = "not_found";
            message = "Method not allowed";
        } else if (status == 415) {
            code = "invalid_json";
            message = "Unsupported content type";
        } else {
            code = "internal";
            message = "Unexpected error";
        }

        String body = MAPPER.writeValueAsString(
                Map.of("error", Map.of("code", code, "message", message)));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
