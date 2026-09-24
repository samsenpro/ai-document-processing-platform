package com.documind.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("""
            select a from AuditLog a
            where a.organizationId = :organizationId
              and (:event is null or a.event = :event)
              and (:documentId is null or a.documentId = :documentId)
            """)
    Page<AuditLog> search(@Param("organizationId") UUID organizationId, @Param("event") AuditEvent event,
                          @Param("documentId") UUID documentId, Pageable pageable);
}
