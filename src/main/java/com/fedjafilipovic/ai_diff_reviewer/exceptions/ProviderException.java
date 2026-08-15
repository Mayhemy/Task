package com.fedjafilipovic.ai_diff_reviewer.exceptions;

/** Any provider failure. JobService turns this into a `failed` job, never an HTTP 5xx. */
public class ProviderException extends Exception {
    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
