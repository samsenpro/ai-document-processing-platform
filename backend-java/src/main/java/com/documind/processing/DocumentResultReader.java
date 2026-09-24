package com.documind.processing;

import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import com.documind.processing.ProcessingDtos.DocumentResultResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Lectura del resultado con caché en Redis. Un resultado no cambia una vez guardado (COMPLETED es
 * un estado final), así que se puede cachear sin riesgo de servir datos obsoletos; solo se invalida
 * al borrar el documento.
 * <p>
 * La autorización se comprueba antes de llegar aquí: la caché nunca decide quién puede leer qué.
 */
@Component
public class DocumentResultReader {

    public static final String CACHE = "document-results";

    private final DocumentResultRepository repository;

    public DocumentResultReader(DocumentResultRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CACHE, key = "#documentId")
    @Transactional(readOnly = true)
    public DocumentResultResponse read(UUID documentId) {
        return repository.findByDocument_Id(documentId)
                .map(DocumentResultResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.RESULT_NOT_AVAILABLE));
    }

    @CacheEvict(cacheNames = CACHE, key = "#documentId")
    public void evict(UUID documentId) {
        // Solo invalida la entrada de caché
    }
}
