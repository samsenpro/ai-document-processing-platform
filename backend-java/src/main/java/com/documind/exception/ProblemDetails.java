package com.documind.exception;

import com.documind.common.web.CorrelationId;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

import java.time.Instant;
import java.util.Map;

/**
 * Construye las respuestas de error en formato RFC 7807. Todas incluyen un {@code code} estable y
 * el {@code correlationId} para poder localizar la petición en los logs.
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(ErrorCode code, String message, Map<String, Object> details) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), message);
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", Instant.now());
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        details.forEach(problem::setProperty);
        return problem;
    }

    public static ProblemDetail of(ErrorCode code) {
        return of(code, code.defaultMessage(), Map.of());
    }
}
