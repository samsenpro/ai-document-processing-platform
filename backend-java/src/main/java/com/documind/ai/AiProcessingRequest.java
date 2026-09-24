package com.documind.ai;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

/** Petición al servicio de IA ({@code POST /api/v1/process}). Python usa snake_case. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiProcessingRequest(UUID documentId, String fileUrl, String documentType, String filename,
                                  String contentType) {
}
