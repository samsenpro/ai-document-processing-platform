package com.documind.ai;

import com.documind.common.web.CorrelationId;
import com.documind.document.DocumentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;

/**
 * Cliente HTTP del servicio de IA protegido con Resilience4j.
 * <p>
 * Orden de los decoradores: Retry envuelve al CircuitBreaker. Cada intento pasa por el circuito;
 * cuando se abre, las llamadas fallan al instante con {@code CallNotPermittedException} (que no se
 * reintenta) y el procesamiento termina en FAILED sin bloquear al worker.
 */
@Component
public class HttpAiProcessingClient implements AiProcessingClient {

    public static final String RESILIENCE_INSTANCE = "aiService";

    private static final Logger log = LoggerFactory.getLogger(HttpAiProcessingClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public HttpAiProcessingClient(@Qualifier("aiRestClient") RestClient restClient, ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public AiProcessingResult process(AiProcessingRequest request) {
        try {
            AiProcessingResult result = restClient.post()
                    .uri("/api/v1/process")
                    .header(CorrelationId.HEADER, MDC.get(CorrelationId.MDC_KEY))
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw toException(response);
                    })
                    .body(AiProcessingResult.class);
            validate(result);
            countRequest("success");
            return result;
        } catch (AiServiceException ex) {
            countRequest(ex instanceof AiProcessingRejectedException ? "rejected" : "error");
            countError(ex.errorCode());
            throw ex;
        } catch (ResourceAccessException ex) {
            boolean timeout = ex.getCause() instanceof HttpTimeoutException;
            String code = timeout ? "AI_SERVICE_TIMEOUT" : "AI_SERVICE_UNAVAILABLE";
            countRequest(timeout ? "timeout" : "error");
            countError(code);
            log.warn("AI service call failed: {} ({})", code, ex.getMessage());
            throw new AiServiceUnavailableException(code, timeout
                    ? "The AI service did not respond in time" : "The AI service is unreachable", ex);
        } catch (RestClientException ex) {
            countRequest("error");
            countError("AI_INVALID_RESPONSE");
            throw new AiProcessingRejectedException("AI_INVALID_RESPONSE", "The AI service returned an invalid response");
        }
    }

    private AiServiceException toException(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String code = "AI_SERVICE_ERROR";
        String message = "AI service returned HTTP " + status.value();
        try {
            JsonNode error = objectMapper.readTree(response.getBody()).path("error");
            code = error.path("code").asText(code);
            message = error.path("message").asText(message);
        } catch (IOException ignored) {
            // Cuerpo vacío o no JSON: se usan los valores por defecto
        }
        log.info("AI service responded {} with {}", status.value(), code);
        if (status.is5xxServerError() || status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new AiServiceUnavailableException(code, message, null);
        }
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            // Error de configuración (la clave interna no coincide): reintentar no lo arreglará
            log.error("AI service rejected the internal API key: check AI_SERVICE_API_KEY on both services");
            return new AiProcessingRejectedException("AI_SERVICE_AUTH_FAILED", "The AI service rejected the credentials");
        }
        return new AiProcessingRejectedException(code, message);
    }

    private void validate(AiProcessingResult result) {
        boolean validType = result != null && result.documentType() != null && Arrays.stream(DocumentType.values())
                .anyMatch(type -> type.name().equals(result.documentType()));
        boolean validConfidence = result != null && result.confidence() != null
                && result.confidence().compareTo(BigDecimal.ZERO) >= 0 && result.confidence().compareTo(BigDecimal.ONE) <= 0;
        if (!validType || !validConfidence || result.text() == null || result.entities() == null
                || !"COMPLETED".equals(result.status())) {
            throw new AiProcessingRejectedException("AI_INVALID_RESPONSE", "The AI service returned an invalid result");
        }
    }

    private void countRequest(String outcome) {
        Counter.builder("ai.requests").description("Requests sent to the AI service")
                .tag("outcome", outcome).register(meterRegistry).increment();
    }

    private void countError(String errorCode) {
        Counter.builder("ai.request.errors").description("Failed requests to the AI service")
                .tag("error_code", errorCode).register(meterRegistry).increment();
    }
}
