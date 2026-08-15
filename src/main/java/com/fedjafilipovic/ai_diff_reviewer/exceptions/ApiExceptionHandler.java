package com.fedjafilipovic.ai_diff_reviewer.exceptions;

import com.fedjafilipovic.ai_diff_reviewer.exceptions.InvalidDiffException;
import com.fedjafilipovic.ai_diff_reviewer.dto.ErrorEnvelope;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.IdempotencyConflictException;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.InvalidJsonException;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.NotFoundException;
import com.fedjafilipovic.ai_diff_reviewer.exceptions.ApiExceptions.PayloadTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps thrown exceptions to error-envelope responses. `unauthorized` and
 * `rate_limited` are handled directly in their filters (they need to run
 * before the dispatcher / need response headers). Spring's own non-2xx
 * responses are handled by EnvelopeErrorController.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ErrorEnvelope> onTooLarge(PayloadTooLargeException e) {
        return envelope(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", e.getMessage());
    }

    @ExceptionHandler(InvalidJsonException.class)
    ResponseEntity<ErrorEnvelope> onBadJson(InvalidJsonException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_json", e.getMessage());
    }

    @ExceptionHandler(InvalidDiffException.class)
    ResponseEntity<ErrorEnvelope> onBadDiff(InvalidDiffException e) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_diff", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorEnvelope> onNotFound(NotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorEnvelope> onConflict(IdempotencyConflictException e) {
        return envelope(HttpStatus.CONFLICT, "idempotency_conflict", e.getMessage());
    }

    // Spring MVC's own exceptions must be handled explicitly, otherwise the
    // generic Exception handler below swallows them and returns 500.

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorEnvelope> onNoResource(NoResourceFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", "No such resource");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorEnvelope> onMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return envelope(HttpStatus.METHOD_NOT_ALLOWED, "not_found", "Method not allowed");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorEnvelope> onUnsupportedMedia(HttpMediaTypeNotSupportedException e) {
        return envelope(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "invalid_json", "Unsupported content type");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> onOther(Exception e) {
        log.error("unexpected error", e);
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Unexpected error");
    }

    private static ResponseEntity<ErrorEnvelope> envelope(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorEnvelope.of(code, message));
    }
}
