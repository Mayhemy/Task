package com.fedjafilipovic.ai_diff_reviewer.web;

/** Error envelope: { "error": { "code": "...", "message": "..." } } */
public record ErrorEnvelope(ApiError error) {

    public static ErrorEnvelope of(String code, String message) {
        return new ErrorEnvelope(new ApiError(code, message));
    }
}
