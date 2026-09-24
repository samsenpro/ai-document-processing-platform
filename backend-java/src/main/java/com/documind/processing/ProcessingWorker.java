package com.documind.processing;

import com.documind.ai.AiProcessingClient;
import com.documind.ai.AiProcessingRequest;
import com.documind.ai.AiProcessingResult;
import com.documind.ai.AiServiceException;
import com.documind.ai.AiServiceProperties;
import com.documind.common.DocumentMetrics;
import com.documind.common.web.CorrelationId;
import com.documind.processing.ProcessingTransitions.WorkItem;
import com.documind.processing.queue.ProcessingJob;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Ejecuta un job de procesamiento: QUEUED → RUNNING → llamada al servicio de IA → COMPLETED/FAILED.
 * <p>
 * Es idempotente: si el mismo job llega dos veces (entrega al menos una vez), solo el primero pasa
 * de QUEUED a RUNNING y el resto se descarta. Ningún error deja el documento en PROCESSING: todo
 * fallo termina en FAILED con un código que explica la causa.
 */
@Component
public class ProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(ProcessingWorker.class);

    private final ProcessingTransitions transitions;
    private final AiProcessingClient aiClient;
    private final ProcessingStateStore stateStore;
    private final DocumentMetrics metrics;
    private final AiServiceProperties aiProperties;
    private final Clock clock;

    public ProcessingWorker(ProcessingTransitions transitions, AiProcessingClient aiClient,
                            ProcessingStateStore stateStore, DocumentMetrics metrics,
                            AiServiceProperties aiProperties, Clock clock) {
        this.transitions = transitions;
        this.aiClient = aiClient;
        this.stateStore = stateStore;
        this.metrics = metrics;
        this.aiProperties = aiProperties;
        this.clock = clock;
    }

    public void handle(ProcessingJob job) {
        MDC.put(CorrelationId.MDC_KEY, job.correlationId() != null ? job.correlationId() : CorrelationId.resolve(null));
        MDC.put("document_id", job.documentId().toString());
        try {
            Optional<WorkItem> item = startProcessing(job);
            if (item.isEmpty()) {
                log.info("Skipping job {}: the processing is no longer queued", job.processingId());
                return;
            }
            process(item.get());
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
            MDC.remove("document_id");
        }
    }

    private Optional<WorkItem> startProcessing(ProcessingJob job) {
        try {
            return transitions.markRunning(job.processingId());
        } catch (OptimisticLockingFailureException ex) {
            // Otro worker tomó el mismo job a la vez y ganó
            return Optional.empty();
        }
    }

    private void process(WorkItem item) {
        log.info("Processing document {} (attempt {})", item.documentId(), item.attempt());
        stateStore.save(item.documentId(), item.processingId(), item.attempt(), ProcessingStage.CALLING_AI_SERVICE);
        try {
            AiProcessingResult result = aiClient.process(new AiProcessingRequest(item.documentId(),
                    sourceUrl(item), item.requestedType(), item.filename(), item.contentType()));
            stateStore.save(item.documentId(), item.processingId(), item.attempt(), ProcessingStage.SAVING_RESULT);
            transitions.complete(item.processingId(), result).ifPresent(duration -> {
                stateStore.save(item.documentId(), item.processingId(), item.attempt(), ProcessingStage.COMPLETED);
                metrics.documentProcessed(result.documentType(), duration);
                log.info("Document {} processed as {} in {} ms", item.documentId(), result.documentType(),
                        duration.toMillis());
            });
        } catch (AiServiceException ex) {
            fail(item, ex.errorCode(), ex.getMessage());
        } catch (CallNotPermittedException ex) {
            fail(item, "AI_SERVICE_CIRCUIT_OPEN",
                    "The AI service is temporarily unavailable, try again in a few moments");
        } catch (RuntimeException ex) {
            log.error("Unexpected error processing document {}", item.documentId(), ex);
            fail(item, "INTERNAL_ERROR", "Unexpected error while processing the document");
        }
    }

    private void fail(WorkItem item, String errorCode, String message) {
        if (transitions.fail(item.processingId(), errorCode, message)) {
            stateStore.save(item.documentId(), item.processingId(), item.attempt(), ProcessingStage.FAILED);
            metrics.documentFailed(errorCode, Duration.between(item.queuedAt(), clock.instant()));
            log.warn("Processing of document {} failed: {} - {}", item.documentId(), errorCode, message);
        }
    }

    /** URL interna desde la que el servicio de IA descarga el archivo (protegida con la clave interna). */
    private String sourceUrl(WorkItem item) {
        return aiProperties.documentSourceBaseUrl().replaceAll("/+$", "")
                + "/internal/v1/documents/" + item.documentId() + "/content";
    }
}
