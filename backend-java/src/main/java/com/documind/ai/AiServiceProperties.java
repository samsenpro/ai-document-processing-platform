package com.documind.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param url                     URL base del servicio de IA (Python)
 * @param apiKey                  clave interna compartida; la envía Java y la exige Python, y viceversa
 * @param documentSourceBaseUrl   URL con la que el servicio de IA llega a este backend para descargar
 *                                el documento (en Docker, el nombre del servicio: http://java-api:8080)
 */
@Validated
@ConfigurationProperties(prefix = "documind.ai-service")
public record AiServiceProperties(
        @NotBlank String url,
        @NotBlank @Size(min = 32, message = "AI_SERVICE_API_KEY must have at least 32 characters") String apiKey,
        @NotBlank String documentSourceBaseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
