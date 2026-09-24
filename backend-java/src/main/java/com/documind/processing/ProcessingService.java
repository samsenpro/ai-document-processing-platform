package com.documind.processing;

import com.documind.audit.AuditEvent;
import com.documind.audit.AuditService;
import com.documind.auth.AuthenticatedUser;
import com.documind.common.web.CorrelationId;
import com.documind.document.Document;
import com.documind.document.DocumentAccess;
import com.documind.document.DocumentStatus;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import com.documind.processing.ProcessingDtos.DocumentResultResponse;
import com.documind.processing.ProcessingDtos.ProcessingAttemptResponse;
import com.documind.processing.ProcessingDtos.ProcessingStatusResponse;
import com.documind.processing.queue.ProcessingJob;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crea procesamientos y expone su estado y su resultado. No procesa nada: solo crea el job
 * (asíncrono) y responde de inmediato; el trabajo lo hace {@link ProcessingWorker}.
 */
@Service
public class ProcessingService {

    private final DocumentProcessingRepository processingRepository;
    private final DocumentAccess documentAccess;
    private final DocumentResultReader resultReader;
    private final ProcessingStateStore stateStore;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ProcessingService(DocumentProcessingRepository processingRepository, DocumentAccess documentAccess,
                             DocumentResultReader resultReader, ProcessingStateStore stateStore,
                             AuditService auditService, ApplicationEventPublisher events, Clock clock) {
        this.processingRepository = processingRepository;
        this.documentAccess = documentAccess;
        this.resultReader = resultReader;
        this.stateStore = stateStore;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    /** Procesa (o reprocesa tras un fallo) un documento existente. */
    @Transactional
    public UUID requestProcessing(AuthenticatedUser user, UUID documentId, String ip) {
        Document document = documentAccess.require(user, documentId);
        return start(document, user, ip).getId();
    }

    /**
     * Crea un intento de procesamiento dentro de la transacción del llamador. La máquina de estados
     * rechaza (409) procesar un documento que ya está en PROCESSING o COMPLETED, y el índice único
     * parcial de la base de datos lo garantiza incluso con peticiones simultáneas.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentProcessing start(Document document, AuthenticatedUser user, String ip) {
        boolean retry = document.getStatus() == DocumentStatus.FAILED;
        document.transitionTo(DocumentStatus.PROCESSING);
        int attempt = processingRepository.countByDocument_Id(document.getId()) + 1;
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        Instant now = clock.instant();

        DocumentProcessing processing;
        try {
            processing = processingRepository.saveAndFlush(
                    new DocumentProcessing(document, attempt, user.id(), correlationId, now));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, "The document is already being processed");
        }
        auditService.record(AuditEvent.PROCESSING_STARTED, document.getOrganizationId(), user.id(),
                document.getId(), ip, Map.of("processingId", processing.getId().toString(), "attempt", attempt,
                        "retry", retry));
        events.publishEvent(new ProcessingRequestedEvent(
                new ProcessingJob(processing.getId(), document.getId(), attempt, correlationId, now)));
        return processing;
    }

    @Transactional(readOnly = true)
    public ProcessingStatusResponse status(AuthenticatedUser user, UUID documentId) {
        Document document = documentAccess.require(user, documentId);
        List<ProcessingAttemptResponse> attempts = processingRepository.findByDocument_IdOrderByAttemptAsc(documentId)
                .stream().map(ProcessingAttemptResponse::from).toList();
        ProcessingAttemptResponse current = attempts.isEmpty() ? null : attempts.getLast();
        // La etapa de Redis solo se muestra si corresponde al intento actual
        ProcessingStage stage = current == null ? null : stateStore.find(documentId)
                .filter(state -> state.processingId().equals(current.id()))
                .map(ProcessingStateStore.ProcessingState::stage)
                .orElse(null);
        return new ProcessingStatusResponse(documentId, document.getStatus(), current, stage, attempts);
    }

    @Transactional(readOnly = true)
    public DocumentResultResponse result(AuthenticatedUser user, UUID documentId) {
        Document document = documentAccess.require(user, documentId);
        if (document.getStatus() != DocumentStatus.COMPLETED) {
            throw new ApiException(ErrorCode.RESULT_NOT_AVAILABLE,
                    "The document has no result yet (status " + document.getStatus() + ")",
                    Map.of("documentStatus", document.getStatus().name()));
        }
        return resultReader.read(documentId);
    }
}
