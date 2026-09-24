package com.documind.processing;

import com.documind.ai.AiProcessingResult;
import com.documind.audit.AuditEvent;
import com.documind.audit.AuditService;
import com.documind.document.Document;
import com.documind.document.DocumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cambios de estado del procesamiento que hace el worker, cada uno en su propia transacción
 * (el worker no mantiene una transacción abierta mientras espera al servicio de IA).
 * <p>
 * Todas las operaciones comprueban el estado actual antes de actuar: un mensaje duplicado, un
 * resultado que llega tarde o una carrera con el {@code StaleProcessingReaper} no pueden dejar el
 * documento en un estado incoherente.
 */
@Service
public class ProcessingTransitions {

    private static final Logger log = LoggerFactory.getLogger(ProcessingTransitions.class);

    private final DocumentProcessingRepository processingRepository;
    private final DocumentResultRepository resultRepository;
    private final AuditService auditService;
    private final Clock clock;

    public ProcessingTransitions(DocumentProcessingRepository processingRepository,
                                 DocumentResultRepository resultRepository, AuditService auditService, Clock clock) {
        this.processingRepository = processingRepository;
        this.resultRepository = resultRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** QUEUED → RUNNING. Vacío si el procesamiento ya no está en cola (mensaje repetido o caducado). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<WorkItem> markRunning(UUID processingId) {
        return processingRepository.findById(processingId)
                .filter(processing -> processing.getStatus() == ProcessingStatus.QUEUED)
                .map(processing -> {
                    processing.start(clock.instant());
                    Document document = processing.getDocument();
                    return new WorkItem(processing.getId(), document.getId(), processing.getAttempt(),
                            processing.getRequestedType().name(), document.getOriginalFilename(),
                            document.getContentType(), processing.getQueuedAt());
                });
    }

    /** RUNNING → COMPLETED: guarda el resultado y completa el documento. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Duration> complete(UUID processingId, AiProcessingResult result) {
        DocumentProcessing processing = processingRepository.findById(processingId).orElse(null);
        if (processing == null || processing.getStatus() != ProcessingStatus.RUNNING) {
            log.warn("Discarding result of processing {}: it is no longer running", processingId);
            return Optional.empty();
        }
        processing.complete(clock.instant());
        Document document = processing.getDocument();
        DocumentType type = DocumentType.valueOf(result.documentType());
        document.markCompleted(type, processing.getDurationMs());
        resultRepository.save(new DocumentResult(document, processing.getId(), type, result.confidence(),
                result.language(), result.text(), result.summary(), result.entities(), result.metadata(),
                result.processingTimeMs()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("processingId", processing.getId().toString());
        metadata.put("attempt", processing.getAttempt());
        metadata.put("documentType", type.name());
        metadata.put("confidence", result.confidence());
        metadata.put("durationMs", processing.getDurationMs());
        auditService.record(AuditEvent.PROCESSING_COMPLETED, document.getOrganizationId(),
                processing.getRequestedBy(), document.getId(), null, metadata);
        return Optional.of(Duration.ofMillis(processing.getDurationMs()));
    }

    /** QUEUED/RUNNING → FAILED. Devuelve false si el procesamiento ya había terminado. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(UUID processingId, String errorCode, String errorMessage) {
        DocumentProcessing processing = processingRepository.findById(processingId).orElse(null);
        if (processing == null || !processing.getStatus().isActive()) {
            return false;
        }
        processing.fail(clock.instant(), errorCode, errorMessage);
        Document document = processing.getDocument();
        document.markFailed();
        auditService.record(AuditEvent.PROCESSING_FAILED, document.getOrganizationId(), processing.getRequestedBy(),
                document.getId(), null, Map.of(
                        "processingId", processing.getId().toString(),
                        "attempt", processing.getAttempt(),
                        "errorCode", errorCode));
        log.info("Processing {} of document {} failed with {}", processingId, document.getId(), errorCode);
        return true;
    }

    /** Datos que el worker necesita para llamar al servicio de IA, leídos al pasar a RUNNING. */
    public record WorkItem(UUID processingId, UUID documentId, int attempt, String requestedType, String filename,
                           String contentType, Instant queuedAt) {
    }
}
