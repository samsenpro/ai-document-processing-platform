package com.documind.auth;

import com.documind.exception.ErrorCode;
import com.documind.exception.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protege los endpoints internos ({@code /internal/**}) que usa el servicio de IA. Solo acepta la
 * clave interna compartida (nunca un JWT de usuario) y la compara en tiempo constante.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String ROLE = "INTERNAL_SERVICE";

    private final byte[] expectedKey;
    private final ProblemResponseWriter problemWriter;

    public InternalApiKeyFilter(String expectedKey, ProblemResponseWriter problemWriter) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            problemWriter.write(response, ErrorCode.AUTHENTICATION_REQUIRED);
            return;
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "ai-service", null, AuthorityUtils.createAuthorityList("ROLE_" + ROLE));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
