package com.documind.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas de negocio. En Prometheus aparecen como {@code documents_uploaded_total},
 * {@code documents_processed_total}, {@code documents_failed_total} y
 * {@code processing_duration_seconds_*}.
 */
@Component
public class DocumentMetrics {

    private final MeterRegistry registry;
    private final Counter uploaded;

    public DocumentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.uploaded = Counter.builder("documents.uploaded").description("Documents uploaded").register(registry);
    }

    public void documentUploaded() {
        uploaded.increment();
    }

    public void documentProcessed(String documentType, Duration duration) {
        Counter.builder("documents.processed").description("Documents processed successfully")
                .tag("document_type", documentType).register(registry).increment();
        processingTimer("completed").record(duration);
    }

    public void documentFailed(String errorCode, Duration duration) {
        Counter.builder("documents.failed").description("Documents whose processing failed")
                .tag("error_code", errorCode).register(registry).increment();
        if (duration != null) {
            processingTimer("failed").record(duration);
        }
    }

    private Timer processingTimer(String outcome) {
        return Timer.builder("processing.duration")
                .description("Time from queueing a document to the end of its processing")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry);
    }
}
