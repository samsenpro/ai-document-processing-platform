package com.documind.common.ratelimit;

import com.documind.auth.AuthenticatedUser;
import com.documind.common.web.ClientIp;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * Aplica las políticas de {@link RateLimited}. Corre después de Spring Security, así que puede
 * contar por usuario autenticado; en endpoints públicos (login, registro) cuenta por IP.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisRateLimiter limiter;
    private final RateLimitProperties properties;

    public RateLimitInterceptor(RedisRateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited annotation = method.getMethodAnnotation(RateLimited.class);
        if (annotation == null) {
            return true;
        }
        RateLimitProperties.Policy policy = properties.policies().get(annotation.policy());
        if (policy == null) {
            throw new IllegalStateException("Unknown rate limit policy: " + annotation.policy());
        }

        String key = "ratelimit:" + annotation.policy() + ":" + subject(request);
        RedisRateLimiter.Decision decision = limiter.check(key, policy);
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        if (!decision.allowed()) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, ErrorCode.RATE_LIMIT_EXCEEDED.defaultMessage(),
                    Map.of("policy", annotation.policy(), "limit", decision.limit()), decision.resetIn());
        }
        return true;
    }

    private static String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return "user:" + user.id();
        }
        return "ip:" + ClientIp.of(request);
    }
}
