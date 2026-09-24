package com.documind.document;

import com.documind.processing.ProcessingDtos.ProcessingAttemptResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    /** Detalle del documento, con su último intento de procesamiento y las acciones permitidas. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentResponse(
            UUID id,
            String filename,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            RequestedDocumentType requestedType,
            DocumentType detectedType,
            DocumentStatus status,
            Long processingTimeMs,
            UUID ownerId,
            ProcessingAttemptResponse latestProcessing,
            Actions actions,
            Instant createdAt,
            Instant updatedAt) {

        public static DocumentResponse from(Document document, ProcessingAttemptResponse latestProcessing) {
            return new DocumentResponse(document.getId(), document.getOriginalFilename(), document.getContentType(),
                    document.getSizeBytes(), document.getChecksumSha256(), document.getRequestedType(),
                    document.getDetectedType(), document.getStatus(), document.getProcessingTimeMs(),
                    document.getOwnerId(), latestProcessing, Actions.of(document.getStatus()),
                    document.getCreatedAt(), document.getUpdatedAt());
        }
    }

    /** Acciones disponibles según la máquina de estados, para que el cliente no tenga que replicarla. */
    public record Actions(boolean process, boolean retry, boolean delete) {

        static Actions of(DocumentStatus status) {
            return new Actions(status == DocumentStatus.UPLOADED, status == DocumentStatus.FAILED,
                    status != DocumentStatus.PROCESSING);
        }
    }

    /** Fila del listado: solo columnas del documento (sin relaciones, sin N+1). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentSummaryResponse(
            UUID id,
            String filename,
            String contentType,
            long sizeBytes,
            RequestedDocumentType requestedType,
            DocumentType detectedType,
            DocumentStatus status,
            Long processingTimeMs,
            UUID ownerId,
            Instant createdAt) {

        public static DocumentSummaryResponse from(Document document) {
            return new DocumentSummaryResponse(document.getId(), document.getOriginalFilename(),
                    document.getContentType(), document.getSizeBytes(), document.getRequestedType(),
                    document.getDetectedType(), document.getStatus(), document.getProcessingTimeMs(),
                    document.getOwnerId(), document.getCreatedAt());
        }
    }

    public record DocumentStatsResponse(
            long total,
            long uploaded,
            long processing,
            long completed,
            long failed,
            Long averageProcessingTimeMs,
            Map<DocumentType, Long> byType) {
    }
}
