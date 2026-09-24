package com.documind.common.idempotency;

import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Idempotencia de las operaciones que crean recursos o disparan procesamiento
 * (cabecera {@code X-Idempotency-Key}).
 * <p>
 * La primera petición reserva la clave en Redis (SET NX) y, al terminar, guarda el ID del recurso
 * creado. Una repetición con la misma clave y el mismo contenido devuelve ese recurso sin volver a
 * ejecutar la operación; con otro contenido se rechaza, y mientras la primera sigue en curso se
 * responde 409. Las claves son por usuario: dos usuarios no pueden interferir entre sí.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "X-Idempotency-Key";

    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");
    private static final String PENDING = "PENDING";
    private static final String DONE = "DONE";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration pendingTtl;

    public IdempotencyService(StringRedisTemplate redis,
                              @Value("${documind.idempotency.ttl:24h}") Duration ttl,
                              @Value("${documind.idempotency.pending-ttl:5m}") Duration pendingTtl) {
        this.redis = redis;
        this.ttl = ttl;
        this.pendingTtl = pendingTtl;
    }

    /**
     * Ejecuta la operación una sola vez por clave. Sin clave, la ejecuta siempre.
     *
     * @param fingerprint resumen del contenido de la petición, para detectar la reutilización de una
     *                    clave con otra petición distinta
     */
    public Outcome execute(UUID userId, String scope, String idempotencyKey, String fingerprint,
                           Supplier<UUID> operation) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new Outcome(operation.get(), false);
        }
        if (!VALID_KEY.matcher(idempotencyKey).matches()) {
            throw new ApiException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "X-Idempotency-Key must have 8-100 characters: letters, digits, '.', '_', ':' or '-'");
        }
        String key = "idempotency:" + scope + ":" + userId + ":" + idempotencyKey;

        Boolean reserved = redis.opsForValue().setIfAbsent(key, PENDING + "|" + fingerprint, pendingTtl);
        if (!Boolean.TRUE.equals(reserved)) {
            return replay(key, fingerprint);
        }
        try {
            UUID resourceId = operation.get();
            redis.opsForValue().set(key, DONE + "|" + fingerprint + "|" + resourceId, ttl);
            return new Outcome(resourceId, false);
        } catch (RuntimeException ex) {
            // Si la operación falla la clave se libera y el cliente puede reintentar con la misma
            redis.delete(key);
            throw ex;
        }
    }

    private Outcome replay(String key, String fingerprint) {
        String stored = redis.opsForValue().get(key);
        if (stored == null) {
            // La reserva expiró entre SET NX y GET: se trata como una petición que sigue en curso
            throw new ApiException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS);
        }
        String[] parts = stored.split("\\|", 3);
        if (!parts[1].equals(fingerprint)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (PENDING.equals(parts[0])) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                    ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS.defaultMessage(), Map.of(), Duration.ofSeconds(2));
        }
        return new Outcome(UUID.fromString(parts[2]), true);
    }

    /** Resultado: el recurso afectado y si es la repetición de una petición anterior. */
    public record Outcome(UUID resourceId, boolean replayed) {
    }
}
