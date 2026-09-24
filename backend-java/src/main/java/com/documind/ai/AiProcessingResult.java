package com.documind.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Resultado estructurado del servicio de IA. {@code entities} y {@code metadata} son mapas porque
 * cada tipo de documento tiene campos distintos; se guardan tal cual en columnas JSONB.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProcessingResult(
        UUID documentId,
        String status,
        String documentType,
        BigDecimal confidence,
        String language,
        String text,
        String summary,
        Map<String, Object> entities,
        long processingTimeMs,
        Map<String, Object> metadata) {
}
