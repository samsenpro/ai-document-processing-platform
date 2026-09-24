package com.documind.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Aplica una política de rate limiting (definida en {@code documind.rate-limit.policies}) al
 * endpoint anotado. El límite se cuenta por usuario autenticado o, si no hay usuario, por IP.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    String policy();
}
