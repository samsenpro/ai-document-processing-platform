package com.documind.document;

import com.documind.document.storage.FileStorageService;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Endpoint interno desde el que el servicio de IA descarga el archivo. Solo acepta la clave interna
 * (ver {@code SecurityConfig}) y solo entrega documentos que se están procesando: aunque la clave se
 * filtrara, no serviría para descargar cualquier documento en cualquier momento.
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/documents")
public class InternalDocumentController {

    private final DocumentRepository documentRepository;
    private final FileStorageService storage;

    public InternalDocumentController(DocumentRepository documentRepository, FileStorageService storage) {
        this.documentRepository = documentRepository;
        this.storage = storage;
    }

    @GetMapping("/{id}/content")
    @Transactional(readOnly = true)
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (document.getStatus() != DocumentStatus.PROCESSING) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, "Document is not being processed",
                    Map.of("currentStatus", document.getStatus().name()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .contentLength(document.getSizeBytes())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(storage.open(document.getStorageKey())));
    }
}
