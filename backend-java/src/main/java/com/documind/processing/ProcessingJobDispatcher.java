package com.documind.processing;

import com.documind.common.DocumentMetrics;
import com.documind.processing.queue.ProcessingJob;
import com.documind.processing.queue.ProcessingJobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publica el job en la cola solo después del commit. Si se publicara dentro de la transacción, un
 * worker podría recibirlo antes de que el procesamiento exista en la base de datos, o recibir un
 * job de una transacción que después se deshizo.
 */
@Component
public class ProcessingJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobDispatcher.class);

    private final ProcessingJobQueue queue;
    private final ProcessingTransitions transitions;
    private final ProcessingStateStore stateStore;
    private final DocumentMetrics metrics;

    public ProcessingJobDispatcher(ProcessingJobQueue queue, ProcessingTransitions transitions,
                                   ProcessingStateStore stateStore, DocumentMetrics metrics) {
        this.queue = queue;
        this.transitions = transitions;
        this.stateStore = stateStore;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProcessingRequested(ProcessingRequestedEvent event) {
        ProcessingJob job = event.job();
        try {
            queue.publish(job);
            stateStore.save(job.documentId(), job.processingId(), job.attempt(), ProcessingStage.QUEUED);
        } catch (RuntimeException ex) {
            // Sin cola no hay procesamiento: se marca FAILED para que el usuario pueda reintentar
            // en lugar de dejar el documento en PROCESSING para siempre
            log.error("Could not enqueue processing {} of document {}", job.processingId(), job.documentId(), ex);
            if (transitions.fail(job.processingId(), "QUEUE_UNAVAILABLE", "The processing queue is unavailable")) {
                metrics.documentFailed("QUEUE_UNAVAILABLE", null);
            }
        }
    }
}
