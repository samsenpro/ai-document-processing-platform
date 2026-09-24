package com.documind.document;

import com.documind.common.BaseEntity;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import com.documind.organization.Organization;
import com.documind.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.Map;
import java.util.UUID;

/**
 * Metadatos de un documento subido. El archivo en sí vive en el {@code FileStorageService}
 * (nunca en PostgreSQL); aquí solo se guarda su clave de almacenamiento.
 */
@Entity
@Table(name = "documents")
public class Document extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(name = "original_filename", nullable = false, updatable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "checksum_sha256", nullable = false, updatable = false, length = 64)
    private String checksumSha256;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_type", nullable = false, updatable = false, length = 20)
    private RequestedDocumentType requestedType;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_type", length = 20)
    private DocumentType detectedType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Column(name = "processing_time_ms")
    private Long processingTimeMs;

    /** Bloqueo optimista: dos peticiones concurrentes no pueden aplicar transiciones sobre la misma versión. */
    @Version
    private long version;

    protected Document() {
        // JPA
    }

    public Document(UUID id, Organization organization, User owner, String originalFilename, String contentType,
                    long sizeBytes, String checksumSha256, String storageKey, RequestedDocumentType requestedType) {
        super(id);
        this.organization = organization;
        this.owner = owner;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.storageKey = storageKey;
        this.requestedType = requestedType;
        this.status = DocumentStatus.UPLOADED;
    }

    /** Aplica una transición de la máquina de estados; si no está permitida responde 409. */
    public void transitionTo(DocumentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Document cannot move from " + status + " to " + target,
                    Map.of("currentStatus", status.name(), "targetStatus", target.name()));
        }
        this.status = target;
    }

    public void markCompleted(DocumentType detectedType, long processingTimeMs) {
        transitionTo(DocumentStatus.COMPLETED);
        this.detectedType = detectedType;
        this.processingTimeMs = processingTimeMs;
    }

    public void markFailed() {
        transitionTo(DocumentStatus.FAILED);
    }

    public UUID getOrganizationId() {
        return organization.getId();
    }

    public UUID getOwnerId() {
        return owner.getId();
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public RequestedDocumentType getRequestedType() {
        return requestedType;
    }

    public DocumentType getDetectedType() {
        return detectedType;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public Long getProcessingTimeMs() {
        return processingTimeMs;
    }
}
