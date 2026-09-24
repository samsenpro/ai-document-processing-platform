package com.documind.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Registra los eventos de auditoría dentro de la transacción de negocio: si la operación se
 * deshace, su entrada de auditoría también (nunca queda constancia de algo que no ocurrió).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event, UUID organizationId, UUID userId, UUID documentId, String ipAddress,
                       Map<String, Object> metadata) {
        repository.save(new AuditLog(organizationId, userId, event, documentId, ipAddress, metadata));
        log.info("Audit event {} user={} document={}", event, userId, documentId);
    }
}
