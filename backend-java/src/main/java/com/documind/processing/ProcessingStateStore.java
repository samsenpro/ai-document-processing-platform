package com.documind.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Estado temporal del procesamiento en Redis: {@code document:processing:{documentId}} (hash con
 * TTL). Permite consultar la etapa exacta de un procesamiento en curso sin tocar la base de datos.
 * <p>
 * Es información auxiliar: si Redis falla no se interrumpe el procesamiento, solo se pierde el
 * detalle de la etapa.
 */
@Component
public class ProcessingStateStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessingStateStore.class);
    private static final String KEY_PREFIX = "document:processing:";

    private final StringRedisTemplate redis;
    private final ProcessingProperties properties;
    private final Clock clock;

    public ProcessingStateStore(StringRedisTemplate redis, ProcessingProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public void save(UUID documentId, UUID processingId, int attempt, ProcessingStage stage) {
        String key = KEY_PREFIX + documentId;
        try {
            redis.opsForHash().putAll(key, Map.of(
                    "processingId", processingId.toString(),
                    "attempt", Integer.toString(attempt),
                    "stage", stage.name(),
                    "updatedAt", clock.instant().toString()));
            redis.expire(key, properties.stateTtl());
        } catch (DataAccessException ex) {
            log.warn("Could not store processing state of document {}: {}", documentId, ex.getMessage());
        }
    }

    public Optional<ProcessingState> find(UUID documentId) {
        try {
            Map<Object, Object> values = redis.opsForHash().entries(KEY_PREFIX + documentId);
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ProcessingState(
                    UUID.fromString((String) values.get("processingId")),
                    Integer.parseInt((String) values.get("attempt")),
                    ProcessingStage.valueOf((String) values.get("stage")),
                    Instant.parse((String) values.get("updatedAt"))));
        } catch (RuntimeException ex) {
            // Redis caído o una entrada con formato inesperado: sin etapa temporal, pero la consulta sigue
            log.warn("Could not read processing state of document {}: {}", documentId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void delete(UUID documentId) {
        try {
            redis.delete(KEY_PREFIX + documentId);
        } catch (DataAccessException ex) {
            log.warn("Could not delete processing state of document {}: {}", documentId, ex.getMessage());
        }
    }

    public record ProcessingState(UUID processingId, int attempt, ProcessingStage stage, Instant updatedAt) {
    }
}
