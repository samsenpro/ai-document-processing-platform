package com.documind.support;

import com.documind.ai.HttpAiProcessingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base de los tests de integración: PostgreSQL y Redis reales (Testcontainers) y el servicio de IA
 * simulado con WireMock. Los contenedores se arrancan una sola vez para toda la batería de tests y
 * todos los tests comparten el mismo contexto de Spring (no se usan propiedades por clase): con dos
 * contextos habría dos grupos de workers compitiendo por la misma cola de Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    public static final String INTERNAL_API_KEY = "test-internal-api-key-0123456789abcdef";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    protected static final WireMockServer AI_SERVICE = new WireMockServer(options().dynamicPort());
    protected static final Path STORAGE;

    static {
        POSTGRES.start();
        REDIS.start();
        AI_SERVICE.start();
        try {
            STORAGE = Files.createTempDirectory("documind-storage");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("documind.security.jwt.secret", () -> "test-jwt-secret-with-at-least-32-bytes-0123456789");
        registry.add("documind.ai-service.url", AI_SERVICE::baseUrl);
        registry.add("documind.ai-service.api-key", () -> INTERNAL_API_KEY);
        registry.add("documind.ai-service.read-timeout", () -> "2s");
        registry.add("documind.storage.local-path", STORAGE::toString);
        registry.add("resilience4j.retry.instances.aiService.wait-duration", () -> "50ms");
        registry.add("documind.processing.reaper-interval", () -> "1h");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener", () -> "WARN");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetExternalState() {
        AI_SERVICE.resetAll();
        circuitBreakerRegistry.circuitBreaker(HttpAiProcessingClient.RESILIENCE_INSTANCE).reset();
        // Los contadores de rate limiting no deben pasar de un test a otro
        var keys = redis.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ---- Usuarios ----

    /** Registra un usuario nuevo (y su organización) y devuelve su sesión. */
    protected Session register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        JsonNode body = json(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "Sup3r-Secret-Pass", "fullName": "Test User",
                                 "organizationName": "Org %s"}""".formatted(email, email)))
                .andExpect(status().isCreated()));
        return Session.from(body);
    }

    /** Crea un miembro de la organización del ADMIN indicado y devuelve su sesión. */
    protected Session createMember(Session admin, String role) throws Exception {
        String email = "member-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/organizations/me/users")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "Sup3r-Secret-Pass", "fullName": "Member", "role": "%s"}"""
                                .formatted(email, role)))
                .andExpect(status().isCreated());
        JsonNode body = json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "Sup3r-Secret-Pass"}""".formatted(email)))
                .andExpect(status().isOk()));
        return Session.from(body);
    }

    // ---- Documentos ----

    protected static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf",
                "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII));
    }

    protected ResultActions upload(Session session, MockMultipartFile file, boolean autoProcess) throws Exception {
        return mockMvc.perform(multipart("/api/v1/documents")
                .file(file)
                .param("autoProcess", Boolean.toString(autoProcess))
                .header(HttpHeaders.AUTHORIZATION, session.bearer()));
    }

    protected UUID uploadDocument(Session session, boolean autoProcess) throws Exception {
        JsonNode body = json(upload(session, pdf("invoice.pdf"), autoProcess).andExpect(status().isCreated()));
        return UUID.fromString(body.path("id").asText());
    }

    protected JsonNode getJson(Session session, String path) throws Exception {
        return json(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk()));
    }

    /** Espera a que el procesamiento asíncrono deje el documento en el estado indicado. */
    protected JsonNode awaitDocumentStatus(Session session, UUID documentId, String expectedStatus) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> getJson(session, "/api/v1/documents/" + documentId),
                        body -> expectedStatus.equals(body.path("status").asText()));
    }

    protected JsonNode json(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return content.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }

    public record Session(UUID userId, UUID organizationId, String accessToken, String refreshToken) {

        static Session from(JsonNode body) {
            return new Session(UUID.fromString(body.path("user").path("id").asText()),
                    UUID.fromString(body.path("user").path("organizationId").asText()),
                    body.path("accessToken").asText(), body.path("refreshToken").asText());
        }

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }
}
