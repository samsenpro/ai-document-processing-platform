package com.documind.auth;

import com.documind.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTest {

    @Test
    void registerCreatesAnOrganizationAndReturnsTokens() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "Ana.Gomez@Example.com", "password": "Sup3r-Secret-Pass",
                                 "fullName": "Ana Gómez", "organizationName": "ACME S.A.S."}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("ana.gomez@example.com"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() throws Exception {
        String body = """
                {"email": "%s", "password": "Sup3r-Secret-Pass", "fullName": "A", "organizationName": "Org"}""";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("dup@example.com")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("DUP@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void validationErrorsAreProblemDetailsWithCorrelationId() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Correlation-Id", "test-correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "short", "fullName": "", "organizationName": "Org"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("X-Correlation-Id", "test-correlation-1"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").value("test-correlation-1"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("email", "password", "fullName")));
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailGivesTheSameError() throws Exception {
        Session session = register();
        String email = getJson(session, "/api/v1/users/me").path("email").asText();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "Wrong-Password-1"}""".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@example.com", "password": "Wrong-Password-1"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshTokenRotatesAndCannotBeReused() throws Exception {
        Session session = register();
        String refresh = """
                {"refreshToken": "%s"}""".formatted(session.refreshToken());

        JsonNode renewed = json(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(refresh))
                .andExpect(status().isOk()));
        assertThat(renewed.path("refreshToken").asText()).isNotEqualTo(session.refreshToken());

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshTokensAreStoredHashedInRedis() throws Exception {
        Session session = register();
        assertThat(redis.keys("auth:refresh:*")).isNotEmpty()
                .noneMatch(key -> key.contains(session.refreshToken()));
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/documents").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsCanManageMembersAndReadTheAuditLog() throws Exception {
        Session admin = register();
        Session member = createMember(admin, "USER");

        mockMvc.perform(get("/api/v1/organizations/me/users").header(HttpHeaders.AUTHORIZATION, member.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/audit-logs").header(HttpHeaders.AUTHORIZATION, member.bearer()))
                .andExpect(status().isForbidden());

        JsonNode members = getJson(admin, "/api/v1/organizations/me/users");
        assertThat(members.path("totalElements").asInt()).isEqualTo(2);
        assertThat(member.organizationId()).isEqualTo(admin.organizationId());

        JsonNode audit = getJson(admin, "/api/v1/audit-logs");
        assertThat(audit.path("content").findValuesAsText("event")).contains("USER_REGISTERED", "USER_CREATED");
    }

    @Test
    void actuatorHealthIsPublicButMetricsNeedAnAdmin() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        Session admin = register();
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk());
    }
}
