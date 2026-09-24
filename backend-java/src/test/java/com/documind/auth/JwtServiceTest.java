package com.documind.auth;

import com.documind.user.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-with-at-least-32-bytes!!";
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(),
            "ana@example.com", Role.USER, "hash", true);

    private JwtService service(String secret, String issuer, Instant now) {
        return new JwtService(new JwtProperties(secret, issuer, Duration.ofMinutes(15), Duration.ofDays(7)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuedTokensIdentifyTheUser() {
        JwtService jwt = service(SECRET, "documind", NOW);
        assertThat(jwt.parseUserId(jwt.createAccessToken(user))).contains(user.id());
        assertThat(jwt.accessTokenTtlSeconds()).isEqualTo(900);
    }

    @Test
    void expiredTamperedOrForeignTokensAreRejected() {
        String token = service(SECRET, "documind", NOW).createAccessToken(user);

        assertThat(service(SECRET, "documind", NOW.plus(Duration.ofMinutes(16))).parseUserId(token)).isEmpty();
        assertThat(service(SECRET, "another-issuer", NOW).parseUserId(token)).isEmpty();
        assertThat(service("another-secret-with-at-least-32-bytes!!", "documind", NOW).parseUserId(token)).isEmpty();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThat(service(SECRET, "documind", NOW).parseUserId(tampered)).isEmpty();
        assertThat(service(SECRET, "documind", NOW).parseUserId("not-a-token")).isEmpty();
    }

    @Test
    void shortSecretsAreRejectedAtStartup() {
        assertThatThrownBy(() -> service("too-short", "documind", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }
}
