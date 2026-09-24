package com.documind.processing;

import com.documind.common.DocumentMetrics;
import com.documind.processing.queue.RedisStreamJobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Tarea de mantenimiento periódica que garantiza que ningún documento se quede bloqueado:
 * <ol>
 *   <li>Reencola los mensajes que un worker recibió y no confirmó (la instancia murió a mitad).</li>
 *   <li>Marca FAILED (PROCESSING_TIMEOUT) los procesamientos que superan el tiempo máximo en cola o
 *       en ejecución; el usuario puede reintentarlos.</li>
 *   <li>Recorta el stream de Redis.</li>
 * </ol>
 * Es seguro ejecutarla en varias instancias a la vez: cada fallo se aplica con bloqueo optimista
 * y comprobando el estado actual.
 */
@Component
public class StaleProcessingReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleProcessingReaper.class);

    private final DocumentProcessingRepository repository;
    private final ProcessingTransitions transitions;
    private final RedisStreamJobQueue queue;
    private final ProcessingProperties properties;
    private final DocumentMetrics metrics;
    private final Clock clock;

    public StaleProcessingReaper(DocumentProcessingRepository repository, ProcessingTransitions transitions,
                                 RedisStreamJobQueue queue, ProcessingProperties properties, DocumentMetrics metrics,
                                 Clock clock) {
        this.repository = repository;
        this.transitions = transitions;
        this.queue = queue;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${documind.processing.reaper-interval:60s}",
            initialDelayString = "${documind.processing.reaper-interval:60s}")
    public void run() {
        requeueAbandonedMessages();
        failStaleProcessings();
    }

    void requeueAbandonedMessages() {
        try {
            int requeued = queue.requeueAbandoned();
            if (requeued > 0) {
                log.warn("Requeued {} abandoned processing jobs", requeued);
            }
            queue.trim();
        } catch (DataAccessException ex) {
            log.warn("Could not inspect the job stream: {}", ex.getMessage());
        }
    }

    void failStaleProcessings() {
        Instant now = clock.instant();
        for (DocumentProcessing processing : repository.findStale(now.minus(properties.queuedTimeout()),
                now.minus(properties.runningTimeout()))) {
            try {
                if (transitions.fail(processing.getId(), "PROCESSING_TIMEOUT",
                        "Processing did not finish in time, it can be retried")) {
                    metrics.documentFailed("PROCESSING_TIMEOUT", null);
                    log.warn("Processing {} of document {} timed out", processing.getId(), processing.getDocumentId());
                }
            } catch (OptimisticLockingFailureException ex) {
                // Terminó justo en este momento: no hay nada que hacer
            } catch (RuntimeException ex) {
                // Un procesamiento problemático no debe impedir revisar el resto
                log.error("Could not fail stale processing {}", processing.getId(), ex);
            }
        }
    }
}
