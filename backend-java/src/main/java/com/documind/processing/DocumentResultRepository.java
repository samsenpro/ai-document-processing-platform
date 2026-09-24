package com.documind.processing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentResultRepository extends JpaRepository<DocumentResult, UUID> {

    Optional<DocumentResult> findByDocument_Id(UUID documentId);
}
