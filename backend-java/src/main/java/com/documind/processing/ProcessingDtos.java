package com.documind.processing;

import com.documind.document.DocumentStatus;
import com.documind.document.DocumentType;
import com.documind.document.RequestedDocumentType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProcessingDtos {

    private ProcessingDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProcessingAttemptResponse(
            UUID id,
            int attempt,
            ProcessingStatus status,
            RequestedDocumentType requestedType,
            Instant queuedAt,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String errorCode,
            String errorMessage) {

        public static ProcessingAttemptResponse from(DocumentProcessing processing) {
            return new ProcessingAttemptResponse(processing.getId(), processing.getAttempt(), processing.getStatus(),
                    processing.getRequestedType(), processing.getQueuedAt(), processing.getStartedAt(),
                    processing.getFinishedAt(), processing.getDurationMs(), processing.getErrorCode(),
                    processing.getErrorMessage());
        }
    }

    /**
     * @param stage etapa en tiempo real (de Redis) del intento en curso; null si no hay información temporal
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProcessingStatusResponse(
            UUID documentId,
            DocumentStatus documentStatus,
            ProcessingAttemptResponse current,
            ProcessingStage stage,
            List<ProcessingAttemptResponse> attempts) {
    }

    public record DocumentResultResponse(
            UUID documentId,
            UUID processingId,
            DocumentType documentType,
            BigDecimal confidence,
            String language,
            String summary,
            Map<String, Object> entities,
            String text,
            long processingTimeMs,
            Map<String, Object> metadata,
            Instant createdAt) {

        public static DocumentResultResponse from(DocumentResult result) {
            return new DocumentResultResponse(result.getDocumentId(), result.getProcessingId(),
                    result.getDocumentType(), result.getConfidence(), result.getLanguage(), result.getSummary(),
                    result.getEntities(), result.getExtractedText(), result.getProcessingTimeMs(),
                    result.getMetadata(), result.getCreatedAt());
        }
    }
}
