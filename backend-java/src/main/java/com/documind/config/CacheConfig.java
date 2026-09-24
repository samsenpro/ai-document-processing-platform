package com.documind.config;

import com.documind.processing.DocumentResultReader;
import com.documind.processing.ProcessingDtos.DocumentResultResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration
public class CacheConfig {

    /**
     * Caché de resultados en Redis, serializada como JSON con un tipo concreto (sin información de
     * clase embebida: una entrada manipulada en Redis no puede instanciar clases arbitrarias).
     */
    @Bean
    RedisCacheManagerBuilderCustomizer documentResultsCache(ObjectMapper objectMapper,
                                                            @Value("${documind.cache.results-ttl:1h}") Duration ttl) {
        var serializer = new Jackson2JsonRedisSerializer<>(objectMapper, DocumentResultResponse.class);
        return builder -> builder.withCacheConfiguration(DocumentResultReader.CACHE,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(ttl)
                        .prefixCacheNameWith("cache:")
                        .disableCachingNullValues()
                        .serializeValuesWith(SerializationPair.fromSerializer(serializer)));
    }
}
