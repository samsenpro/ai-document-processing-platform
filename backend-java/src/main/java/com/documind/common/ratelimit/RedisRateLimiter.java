package com.documind.common.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Rate limiter de ventana fija sobre Redis, compartido por todas las instancias de la API.
 * <p>
 * El incremento y la expiración van en un script Lua para que sean atómicos: con dos comandos
 * sueltos una caída entre ambos dejaría una clave sin TTL que bloquearía al usuario para siempre.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {current, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final Counter redisFailures;

    public RedisRateLimiter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.redisFailures = Counter.builder("rate_limiter.redis.failures")
                .description("Rate limit checks skipped because Redis was unavailable")
                .register(meterRegistry);
    }

    public Decision check(String key, RateLimitProperties.Policy policy) {
        try {
            List<?> result = redis.execute(SCRIPT, List.of(key), Long.toString(policy.window().toMillis()));
            long count = ((Number) result.get(0)).longValue();
            long ttlMillis = Math.max(((Number) result.get(1)).longValue(), 0);
            long remaining = Math.max(policy.limit() - count, 0);
            return new Decision(count <= policy.limit(), policy.limit(), remaining, Duration.ofMillis(ttlMillis));
        } catch (DataAccessException ex) {
            // Se prioriza la disponibilidad: sin Redis no se limita, pero queda registrado en métricas y logs
            redisFailures.increment();
            log.warn("Rate limit check skipped, Redis unavailable: {}", ex.getMessage());
            return new Decision(true, policy.limit(), policy.limit(), Duration.ZERO);
        }
    }

    public record Decision(boolean allowed, int limit, long remaining, Duration resetIn) {
    }
}
