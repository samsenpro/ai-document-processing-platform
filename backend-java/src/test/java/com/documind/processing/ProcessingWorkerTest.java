package com.documind.processing;

import com.documind.ai.AiProcessingClient;
import com.documind.ai.AiProcessingRejectedException;
import com.documind.ai.AiProcessingRequest;
import com.documind.ai.AiProcessingResult;
import com.documind.ai.AiServiceProperties;
import com.documind.ai.AiServiceUnavailableException;
import com.documind.common.DocumentMetrics;
import com.documind.processing.ProcessingTransitions.WorkItem;
import com.documind.processing.queue.ProcessingJob;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessingWorkerTest {

    private final ProcessingTransitions transitions = mock(ProcessingTransitions.class);
    private final AiProcessingClient aiClient = mock(AiProcessingClient.class);
    private final ProcessingStateStore stateStore = mock(ProcessingStateStore.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ProcessingWorker worker;

    private final UUID processingId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final ProcessingJob job = new ProcessingJob(processingId, documentId, 1, "corr-1", Instant.now());
    private final WorkItem item = new WorkItem(processingId, documentId, 1, "AUTO", "factura.pdf", "application/pdf",
            Instant.now());

    @BeforeEach
    void setUp() {
        AiServiceProperties properties = new AiServiceProperties("http://python-ai:8000", "k".repeat(32),
                "http://java-api:8080/", Duration.ofSeconds(1), Duration.ofSeconds(5));
        worker = new ProcessingWorker(transitions, aiClient, stateStore, new DocumentMetrics(meterRegistry),
                properties, Clock.systemUTC());
    }

    @Test
    void successfulProcessingStoresTheResultAndRecordsMetrics() {
        AiProcessingResult result = new AiProcessingResult(documentId, "COMPLETED", "INVOICE", new BigDecimal("0.9"),
                "es", "texto", "resumen", Map.of("total", 100), 50, Map.of());
        when(transitions.markRunning(processingId)).thenReturn(Optional.of(item));
        when(aiClient.process(any())).thenReturn(result);
        when(transitions.complete(processingId, result)).thenReturn(Optional.of(Duration.ofMillis(800)));

        worker.handle(job);

        ArgumentCaptor<AiProcessingRequest> request = ArgumentCaptor.forClass(AiProcessingRequest.class);
        verify(aiClient).process(request.capture());
        assertThat(request.getValue().fileUrl())
                .isEqualTo("http://java-api:8080/internal/v1/documents/" + documentId + "/content");
        assertThat(request.getValue().documentType()).isEqualTo("AUTO");
        verify(stateStore).save(documentId, processingId, 1, ProcessingStage.COMPLETED);
        assertThat(meterRegistry.get("documents.processed").tag("document_type", "INVOICE").counter().count())
                .isEqualTo(1);
        verify(transitions, never()).fail(any(), anyString(), anyString());
    }

    @Test
    void duplicateOrExpiredJobsAreIgnored() {
        when(transitions.markRunning(processingId)).thenReturn(Optional.empty());
        worker.handle(job);
        verify(aiClient, never()).process(any());
    }

    @Test
    void aConcurrentWorkerWinningTheJobIsNotAnError() {
        when(transitions.markRunning(processingId))
                .thenThrow(new ObjectOptimisticLockingFailureException(DocumentProcessing.class, processingId));
        worker.handle(job);
        verify(aiClient, never()).process(any());
    }

    @Test
    void aiErrorsFailTheProcessingWithTheirCode() {
        when(transitions.markRunning(processingId)).thenReturn(Optional.of(item));
        when(transitions.fail(eq(processingId), anyString(), anyString())).thenReturn(true);
        when(aiClient.process(any())).thenThrow(new AiProcessingRejectedException("UNSUPPORTED_FORMAT", "bad file"));

        worker.handle(job);

        verify(transitions).fail(processingId, "UNSUPPORTED_FORMAT", "bad file");
        verify(stateStore).save(documentId, processingId, 1, ProcessingStage.FAILED);
        assertThat(meterRegistry.get("documents.failed").tag("error_code", "UNSUPPORTED_FORMAT").counter().count())
                .isEqualTo(1);
    }

    @Test
    void anOpenCircuitFailsFastWithAClearCode() {
        when(transitions.markRunning(processingId)).thenReturn(Optional.of(item));
        when(transitions.fail(eq(processingId), anyString(), anyString())).thenReturn(true);
        when(aiClient.process(any())).thenThrow(
                CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("aiService")));

        worker.handle(job);

        verify(transitions).fail(eq(processingId), eq("AI_SERVICE_CIRCUIT_OPEN"), anyString());
    }

    @Test
    void unexpectedErrorsNeverLeaveTheDocumentProcessing() {
        when(transitions.markRunning(processingId)).thenReturn(Optional.of(item));
        when(aiClient.process(any())).thenThrow(new AiServiceUnavailableException("AI_SERVICE_UNAVAILABLE", "down", null));
        worker.handle(job);
        verify(transitions).fail(processingId, "AI_SERVICE_UNAVAILABLE", "down");

        doThrow(new IllegalStateException("boom")).when(aiClient).process(any());
        worker.handle(job);
        verify(transitions).fail(eq(processingId), eq("INTERNAL_ERROR"), anyString());
    }
}
