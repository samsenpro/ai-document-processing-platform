package com.documind.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(UUID id, AuditEvent event, UUID userId, UUID documentId, String ipAddress,
                               Map<String, Object> metadata, Instant createdAt) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getEvent(), log.getUserId(), log.getDocumentId(),
                log.getIpAddress(), log.getMetadata(), log.getCreatedAt());
    }
}
