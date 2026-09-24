package com.documind.common.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "documind.rate-limit")
public record RateLimitProperties(boolean enabled, @NotNull Map<String, @Valid Policy> policies) {

    public record Policy(@Positive int limit, @NotNull Duration window) {
    }
}
