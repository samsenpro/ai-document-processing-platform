package com.documind.processing;

import com.documind.common.BaseEntity;
import com.documind.document.Document;
import com.documind.document.RequestedDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Un intento de procesamiento de un documento: el primero o un reintento tras un fallo. */
@Entity
@Table(name = "document_processings")
public class DocumentProcessing extends BaseEntity {

    private static final int MAX_ERROR_MESSAGE = 500;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProcessingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_type", nullable = false, updatable = false, length = 20)
    private RequestedDocumentType requestedType;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE)
    private String errorMessage;

    /** Bloqueo optimista: si dos workers reciben el mismo job, solo uno consigue pasarlo a RUNNING. */
    @Version
    private long version;

    protected DocumentProcessing() {
        // JPA
    }

    public DocumentProcessing(Document document, int attempt, UUID requestedBy, String correlationId,
                              Instant queuedAt) {
        super(UUID.randomUUID());
        this.document = document;
        this.attempt = attempt;
        this.requestedType = document.getRequestedType();
        this.requestedBy = requestedBy;
        this.correlationId = correlationId;
        this.queuedAt = queuedAt;
        this.status = ProcessingStatus.QUEUED;
    }

    public void start(Instant now) {
        requireStatus(ProcessingStatus.QUEUED);
        this.status = ProcessingStatus.RUNNING;
        this.startedAt = now;
    }

    public void complete(Instant now) {
        requireStatus(ProcessingStatus.RUNNING);
        this.status = ProcessingStatus.COMPLETED;
        finish(now);
    }

    public void fail(Instant now, String errorCode, String errorMessage) {
        if (!status.isActive()) {
            throw new IllegalStateException("Processing " + getId() + " is already " + status);
        }
        this.status = ProcessingStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage == null || errorMessage.length() <= MAX_ERROR_MESSAGE
                ? errorMessage : errorMessage.substring(0, MAX_ERROR_MESSAGE);
        finish(now);
    }

    private void finish(Instant now) {
        this.finishedAt = now;
        // La duración cuenta desde que se encoló: es el tiempo que el usuario espera el resultado
        this.durationMs = Duration.between(queuedAt, now).toMillis();
    }

    private void requireStatus(ProcessingStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Processing " + getId() + " is " + status + ", expected " + expected);
        }
    }

    public Document getDocument() {
        return document;
    }

    public UUID getDocumentId() {
        return document.getId();
    }

    public int getAttempt() {
        return attempt;
    }

    public ProcessingStatus getStatus() {
        return status;
    }

    public RequestedDocumentType getRequestedType() {
        return requestedType;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
