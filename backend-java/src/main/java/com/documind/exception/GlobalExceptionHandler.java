package com.documind.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/**
 * Traduce cualquier excepción a un Problem Detail (RFC 7807). Nunca devuelve trazas ni mensajes
 * internos: los errores inesperados se registran completos y el cliente solo recibe INTERNAL_ERROR
 * con el correlation ID para buscarlos en los logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        if (ex.code().status().is5xxServerError()) {
            log.warn("Request failed with {}: {}", ex.code(), ex.getMessage());
        } else {
            log.debug("Request rejected with {}: {}", ex.code(), ex.getMessage());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.code().status());
        if (ex.retryAfter() != null) {
            // Redondeo hacia arriba: un Retry-After de 0 invitaría a reintentar demasiado pronto
            long seconds = Math.max(1, (ex.retryAfter().toMillis() + 999) / 1000);
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
        return builder.body(ProblemDetails.of(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                Map.of("errors", errors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex) {
        List<String> errors = ex.getAllErrors().stream().map(error -> error.getDefaultMessage()).toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                Map.of("errors", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        // El mensaje de Jackson puede incluir fragmentos del cuerpo: no se devuelve
        return build(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.defaultMessage(), Map.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class,
            MultipartException.class})
    public ResponseEntity<ProblemDetail> handleBadParameter(Exception ex) {
        if (ex instanceof MaxUploadSizeExceededException) {
            return build(ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.defaultMessage(), Map.of());
        }
        String message = switch (ex) {
            case MissingServletRequestParameterException e -> "Missing request parameter '" + e.getParameterName() + "'";
            case MissingRequestHeaderException e -> "Missing request header '" + e.getHeaderName() + "'";
            case MethodArgumentTypeMismatchException e -> "Invalid value for parameter '" + e.getName() + "'";
            case MissingServletRequestPartException e -> "Missing multipart part '" + e.getRequestPartName() + "'";
            default -> "Invalid multipart request";
        };
        return build(ErrorCode.VALIDATION_FAILED, message, Map.of());
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ProblemDetail> handleInvalidSort(PropertyReferenceException ex) {
        return build(ErrorCode.VALIDATION_FAILED, "Invalid sort property '" + ex.getPropertyName() + "'", Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.defaultMessage(), Map.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return build(ErrorCode.CONCURRENT_MODIFICATION, ErrorCode.CONCURRENT_MODIFICATION.defaultMessage(), Map.of());
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ProblemDetail> handleRedisDown(RedisConnectionFailureException ex) {
        log.error("Redis is unavailable: {}", ex.getMessage());
        return build(ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE.defaultMessage(), Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Unsupported Content-Type", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoRoute(NoResourceFoundException ex) {
        return build(ErrorCode.ROUTE_NOT_FOUND, ErrorCode.ROUTE_NOT_FOUND.defaultMessage(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), Map.of());
    }

    private Map<String, String> toFieldError(FieldError error) {
        return Map.of("field", error.getField(),
                "message", error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage());
    }

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(code.status()).body(ProblemDetails.of(code, message, details));
    }
}
