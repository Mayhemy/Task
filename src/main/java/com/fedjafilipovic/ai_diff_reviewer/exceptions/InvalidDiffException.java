package com.fedjafilipovic.ai_diff_reviewer.exceptions;

/** Thrown when the diff field is missing, empty, or has no parseable hunks. Maps to 422. */
public class InvalidDiffException extends RuntimeException {
    public InvalidDiffException(String message) {
        super(message);
    }
}
