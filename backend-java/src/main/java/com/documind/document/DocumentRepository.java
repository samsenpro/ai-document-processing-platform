package com.documind.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndOwner_Id(UUID id, UUID ownerId);

    Optional<Document> findByIdAndOrganization_Id(UUID id, UUID organizationId);

    /**
     * Listado paginado. {@code ownerId} nulo = todos los de la organización (ADMIN). La consulta no
     * carga relaciones: el listado solo usa columnas propias del documento, sin consultas N+1.
     */
    @Query("""
            select d from Document d
            where d.organization.id = :organizationId
              and (:ownerId is null or d.owner.id = :ownerId)
              and (:status is null or d.status = :status)
              and (:type is null or d.detectedType = :type)
            """)
    Page<Document> search(@Param("organizationId") UUID organizationId, @Param("ownerId") UUID ownerId,
                          @Param("status") DocumentStatus status, @Param("type") DocumentType type,
                          Pageable pageable);

    @Query("""
            select d.status as status, count(d) as total from Document d
            where d.organization.id = :organizationId and (:ownerId is null or d.owner.id = :ownerId)
            group by d.status
            """)
    List<StatusCount> countByStatus(@Param("organizationId") UUID organizationId, @Param("ownerId") UUID ownerId);

    @Query("""
            select d.detectedType as type, count(d) as total from Document d
            where d.organization.id = :organizationId and (:ownerId is null or d.owner.id = :ownerId)
              and d.detectedType is not null
            group by d.detectedType
            """)
    List<TypeCount> countByType(@Param("organizationId") UUID organizationId, @Param("ownerId") UUID ownerId);

    @Query("""
            select avg(d.processingTimeMs) from Document d
            where d.organization.id = :organizationId and (:ownerId is null or d.owner.id = :ownerId)
              and d.status = com.documind.document.DocumentStatus.COMPLETED
            """)
    Double averageProcessingTimeMs(@Param("organizationId") UUID organizationId, @Param("ownerId") UUID ownerId);

    interface StatusCount {
        DocumentStatus getStatus();

        long getTotal();
    }

    interface TypeCount {
        DocumentType getType();

        long getTotal();
    }
}
