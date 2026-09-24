package com.documind.processing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentProcessingRepository extends JpaRepository<DocumentProcessing, UUID> {

    List<DocumentProcessing> findByDocument_IdOrderByAttemptAsc(UUID documentId);

    Optional<DocumentProcessing> findFirstByDocument_IdOrderByAttemptDesc(UUID documentId);

    int countByDocument_Id(UUID documentId);

    /** Procesamientos que llevan demasiado tiempo en cola o en ejecución (worker caído, mensaje perdido...). */
    @Query("""
            select p from DocumentProcessing p
            where (p.status = com.documind.processing.ProcessingStatus.QUEUED and p.queuedAt < :queuedBefore)
               or (p.status = com.documind.processing.ProcessingStatus.RUNNING and p.startedAt < :runningBefore)
            """)
    List<DocumentProcessing> findStale(@Param("queuedBefore") Instant queuedBefore,
                                       @Param("runningBefore") Instant runningBefore);
}
