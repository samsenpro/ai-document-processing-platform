package com.documind.processing.queue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Mensaje de la cola. Solo lleva identificadores: el worker lee el estado actual de la base de
 * datos, así un mensaje duplicado o antiguo nunca aplica datos obsoletos.
 */
public record ProcessingJob(UUID processingId, UUID documentId, int attempt, String correlationId,
                            Instant enqueuedAt) {

    public Map<String, String> toMap() {
        return Map.of(
                "processingId", processingId.toString(),
                "documentId", documentId.toString(),
                "attempt", Integer.toString(attempt),
                "correlationId", correlationId == null ? "" : correlationId,
                "enqueuedAt", enqueuedAt.toString());
    }

    public static ProcessingJob fromMap(Map<String, String> values) {
        String correlationId = values.get("correlationId");
        return new ProcessingJob(
                UUID.fromString(values.get("processingId")),
                UUID.fromString(values.get("documentId")),
                Integer.parseInt(values.get("attempt")),
                correlationId == null || correlationId.isBlank() ? null : correlationId,
                Instant.parse(values.get("enqueuedAt")));
    }
}
