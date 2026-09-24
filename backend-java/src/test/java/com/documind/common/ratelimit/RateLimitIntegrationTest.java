package com.documind.common.ratelimit;

import com.documind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitIntegrationTest extends IntegrationTest {

    @Test
    void loginAttemptsAreLimitedPerIp() throws Exception {
        String body = """
                {"email": "attacker-target@example.com", "password": "Wrong-Password-1"}""";
        // Política "auth": 10 peticiones por minuto
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(9 - i)));
        }
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.policy").value("auth"));
    }

    @Test
    void processingRequestsAreLimitedPerUser() throws Exception {
        Session session = register();
        // Política "process": 10 por minuto. Las peticiones de un documento inexistente también cuentan.
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/documents/" + UUID.randomUUID() + "/process")
                            .header("Authorization", session.bearer()))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/api/v1/documents/" + UUID.randomUUID() + "/process")
                        .header("Authorization", session.bearer()))
                .andExpect(status().isTooManyRequests());

        // Otro usuario tiene su propio cupo
        Session other = register();
        mockMvc.perform(post("/api/v1/documents/" + UUID.randomUUID() + "/process")
                        .header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
    }
}
