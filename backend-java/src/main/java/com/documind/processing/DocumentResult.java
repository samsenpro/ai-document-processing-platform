package com.documind.processing;

import com.documind.common.BaseEntity;
import com.documind.document.Document;
import com.documind.document.DocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Resultado del procesamiento de un documento. Las entidades extraídas varían según el tipo de
 * documento, por eso se guardan en JSONB (modelo flexible) y no en columnas fijas.
 */
@Entity
@Table(name = "document_results")
public class DocumentResult extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false, unique = true)
    private Document document;

    @Column(name = "processing_id", nullable = false, updatable = false)
    private UUID processingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private DocumentType documentType;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(length = 10)
    private String language;

    @Column(name = "extracted_text", nullable = false, columnDefinition = "TEXT")
    private String extractedText;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> entities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> metadata;

    @Column(name = "processing_time_ms", nullable = false)
    private long processingTimeMs;

    protected DocumentResult() {
        // JPA
    }

    public DocumentResult(Document document, UUID processingId, DocumentType documentType, BigDecimal confidence,
                          String language, String extractedText, String summary, Map<String, Object> entities,
                          Map<String, Object> metadata, long processingTimeMs) {
        super(UUID.randomUUID());
        this.document = document;
        this.processingId = processingId;
        this.documentType = documentType;
        this.confidence = confidence;
        this.language = language;
        this.extractedText = extractedText;
        this.summary = summary == null ? "" : summary;
        this.entities = entities;
        this.metadata = metadata == null ? Map.of() : metadata;
        this.processingTimeMs = processingTimeMs;
    }

    public UUID getDocumentId() {
        return document.getId();
    }

    public UUID getProcessingId() {
        return processingId;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getLanguage() {
        return language;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public String getSummary() {
        return summary;
    }

    public Map<String, Object> getEntities() {
        return entities;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }
}
