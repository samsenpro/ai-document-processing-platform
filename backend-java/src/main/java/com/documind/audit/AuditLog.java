package com.documind.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Registro inmutable de un evento relevante. Guarda IDs sueltos (no relaciones JPA): la entrada
 * debe sobrevivir al borrado del documento y leerla nunca debe cargar otras entidades.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private AuditEvent event;

    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
        // JPA
    }

    public AuditLog(UUID organizationId, UUID userId, AuditEvent event, UUID documentId, String ipAddress,
                    Map<String, Object> metadata) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.userId = userId;
        this.event = event;
        this.documentId = documentId;
        this.ipAddress = ipAddress;
        this.metadata = Map.copyOf(metadata);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public AuditEvent getEvent() {
        return event;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
