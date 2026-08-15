package com.fedjafilipovic.ai_diff_reviewer.exceptions;

/** Exceptions mapped to error-envelope responses by ApiExceptionHandler. */
public final class ApiExceptions {

    private ApiExceptions() {}

    /** 413 payload_too_large */
    public static class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException() { super("Body exceeds 1 MiB"); }
    }

    /** 400 invalid_json — malformed JSON, wrong shape, or invalid option values. */
    public static class InvalidJsonException extends RuntimeException {
        public InvalidJsonException(String message) { super(message); }
    }

    /** 404 not_found */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException() { super("No job with that id"); }
    }

    /** 409 idempotency_conflict */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() { super("Same Idempotency-Key with a different body"); }
    }
}
