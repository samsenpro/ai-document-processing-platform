package com.documind.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Deja el correlation ID en el MDC (aparece en cada línea de log) y lo devuelve en la respuesta.
 * El mismo ID viaja en el job de procesamiento y en las llamadas al servicio de IA, así que una
 * subida se puede seguir de punta a punta entre Java y Python.
 * <p>
 * Se ejecuta antes que Spring Security para que también los 401/403 lleven correlation ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        response.setHeader(CorrelationId.HEADER, correlationId);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
