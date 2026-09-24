package com.documind.document;

import com.documind.audit.AuditEvent;
import com.documind.audit.AuditService;
import com.documind.auth.AuthenticatedUser;
import com.documind.common.DocumentMetrics;
import com.documind.common.idempotency.IdempotencyService;
import com.documind.common.web.PageResponse;
import com.documind.document.DocumentDtos.DocumentResponse;
import com.documind.document.DocumentDtos.DocumentStatsResponse;
import com.documind.document.DocumentDtos.DocumentSummaryResponse;
import com.documind.document.storage.FileStorageService;
import com.documind.document.storage.StoredFile;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import com.documind.organization.OrganizationRepository;
import com.documind.processing.DocumentProcessingRepository;
import com.documind.processing.DocumentResultReader;
import com.documind.processing.ProcessingDtos.ProcessingAttemptResponse;
import com.documind.processing.ProcessingService;
import com.documind.processing.ProcessingStateStore;
import com.documind.user.UserRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentProcessingRepository processingRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final DocumentAccess documentAccess;
    private final DocumentFileValidator fileValidator;
    private final FileStorageService storage;
    private final ProcessingService processingService;
    private final ProcessingStateStore stateStore;
    private final DocumentResultReader resultReader;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final DocumentMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public DocumentService(DocumentRepository documentRepository, DocumentProcessingRepository processingRepository,
                           OrganizationRepository organizationRepository, UserRepository userRepository,
                           DocumentAccess documentAccess, DocumentFileValidator fileValidator,
                           FileStorageService storage, ProcessingService processingService,
                           ProcessingStateStore stateStore, DocumentResultReader resultReader,
                           IdempotencyService idempotencyService, AuditService auditService,
                           DocumentMetrics metrics, TransactionTemplate transactionTemplate) {
        this.documentRepository = documentRepository;
        this.processingRepository = processingRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.documentAccess = documentAccess;
        this.fileValidator = fileValidator;
        this.storage = storage;
        this.processingService = processingService;
        this.stateStore = stateStore;
        this.resultReader = resultReader;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Valida el archivo, lo guarda en el almacenamiento, registra el documento y, si se pide, crea su
     * procesamiento. El archivo se guarda fuera de la transacción (no se retiene una conexión de base
     * de datos mientras se escribe en disco); si la transacción falla, el archivo se borra.
     */
    public UploadResult upload(AuthenticatedUser user, MultipartFile file, RequestedDocumentType requestedType,
                               boolean autoProcess, String idempotencyKey, String ip) {
        DocumentFormat format = fileValidator.validate(file);
        String filename = fileValidator.sanitizeFilename(file.getOriginalFilename());
        String fingerprint = idempotencyKey == null ? "" : sha256(file) + ":" + requestedType + ":" + autoProcess;

        IdempotencyService.Outcome outcome = idempotencyService.execute(user.id(), "upload", idempotencyKey,
                fingerprint, () -> store(user, file, format, filename, requestedType, autoProcess, ip));
        DocumentResponse response = transactionTemplate.execute(status -> get(user, outcome.resourceId()));
        return new UploadResult(response, outcome.replayed());
    }

    private UUID store(AuthenticatedUser user, MultipartFile file, DocumentFormat format, String filename,
                       RequestedDocumentType requestedType, boolean autoProcess, String ip) {
        UUID documentId = UUID.randomUUID();
        // La clave no contiene el nombre original: el usuario no controla ninguna parte de la ruta
        String storageKey = user.organizationId() + "/" + documentId;
        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = storage.store(storageKey, content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Document document = documentRepository.save(new Document(documentId,
                        organizationRepository.getReferenceById(user.organizationId()),
                        userRepository.getReferenceById(user.id()), filename, format.mimeType(),
                        stored.sizeBytes(), stored.sha256(), storageKey, requestedType));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("filename", filename);
                metadata.put("contentType", format.mimeType());
                metadata.put("sizeBytes", stored.sizeBytes());
                metadata.put("requestedType", requestedType.name());
                metadata.put("autoProcess", autoProcess);
                auditService.record(AuditEvent.DOCUMENT_UPLOADED, user.organizationId(), user.id(), documentId, ip,
                        metadata);
                if (autoProcess) {
                    processingService.start(document, user, ip);
                }
            });
        } catch (RuntimeException ex) {
            storage.delete(storageKey);
            throw ex;
        }
        metrics.documentUploaded();
        return documentId;
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentSummaryResponse> list(AuthenticatedUser user, DocumentStatus status,
                                                      DocumentType type, Pageable pageable) {
        UUID ownerFilter = user.isAdmin() ? null : user.id();
        return PageResponse.of(documentRepository.search(user.organizationId(), ownerFilter, status, type, pageable),
                DocumentSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(AuthenticatedUser user, UUID documentId) {
        Document document = documentAccess.require(user, documentId);
        ProcessingAttemptResponse latest = processingRepository.findFirstByDocument_IdOrderByAttemptDesc(documentId)
                .map(ProcessingAttemptResponse::from).orElse(null);
        return DocumentResponse.from(document, latest);
    }

    /**
     * Borra el documento, sus procesamientos y su resultado (en cascada en la base de datos) y, tras
     * el commit, el archivo. La auditoría conserva el evento aunque el documento ya no exista.
     */
    @Transactional
    public void delete(AuthenticatedUser user, UUID documentId, String ip) {
        Document document = documentAccess.require(user, documentId);
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "A document cannot be deleted while it is being processed",
                    Map.of("currentStatus", document.getStatus().name()));
        }
        String storageKey = document.getStorageKey();
        documentRepository.delete(document);
        auditService.record(AuditEvent.DOCUMENT_DELETED, user.organizationId(), user.id(), documentId, ip,
                Map.of("filename", document.getOriginalFilename(), "status", document.getStatus().name()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.delete(storageKey);
                resultReader.evict(documentId);
                stateStore.delete(documentId);
            }
        });
    }

    @Transactional(readOnly = true)
    public DocumentStatsResponse stats(AuthenticatedUser user) {
        UUID ownerFilter = user.isAdmin() ? null : user.id();
        Map<DocumentStatus, Long> byStatus = new EnumMap<>(DocumentStatus.class);
        documentRepository.countByStatus(user.organizationId(), ownerFilter)
                .forEach(row -> byStatus.put(row.getStatus(), row.getTotal()));
        Map<DocumentType, Long> byType = new EnumMap<>(DocumentType.class);
        documentRepository.countByType(user.organizationId(), ownerFilter)
                .forEach(row -> byType.put(row.getType(), row.getTotal()));
        Double average = documentRepository.averageProcessingTimeMs(user.organizationId(), ownerFilter);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new DocumentStatsResponse(total, byStatus.getOrDefault(DocumentStatus.UPLOADED, 0L),
                byStatus.getOrDefault(DocumentStatus.PROCESSING, 0L), byStatus.getOrDefault(DocumentStatus.COMPLETED, 0L),
                byStatus.getOrDefault(DocumentStatus.FAILED, 0L), average == null ? null : Math.round(average), byType);
    }

    private static String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /** Resultado de la subida: el documento y si la respuesta es la repetición de una petición idempotente. */
    public record UploadResult(DocumentResponse document, boolean replayed) {
    }
}
