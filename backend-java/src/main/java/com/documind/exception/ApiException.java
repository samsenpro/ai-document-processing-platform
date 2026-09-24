package com.documind.exception;

import java.time.Duration;
import java.util.Map;

/**
 * Error de negocio con código estable. El {@code GlobalExceptionHandler} lo convierte en una
 * respuesta RFC 7807 (application/problem+json).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;
    private final Duration retryAfter;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), Map.of(), null);
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details, Duration retryAfter) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
        this.retryAfter = retryAfter;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
