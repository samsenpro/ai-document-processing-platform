package com.documind.processing;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param queuedTimeout  tiempo máximo en cola antes de dar el procesamiento por perdido
 * @param runningTimeout tiempo máximo en ejecución; debe superar el peor caso de la llamada al
 *                       servicio de IA con todos sus reintentos
 * @param stateTtl       cuánto vive en Redis el estado temporal {@code document:processing:{id}}
 */
@Validated
@ConfigurationProperties(prefix = "documind.processing")
public record ProcessingProperties(
        @NotNull Duration queuedTimeout,
        @NotNull Duration runningTimeout,
        @NotNull Duration stateTtl) {
}
