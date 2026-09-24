package com.documind.auth;

import com.documind.user.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Peticiones y respuestas de autenticación. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @Schema(example = "ana@acme.com")
            @NotBlank @Email @Size(max = 254) String email,
            @Schema(example = "S3cure-Passw0rd!")
            @NotBlank @Size(min = 12, max = 72, message = "must have between 12 and 72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "must contain letters and digits")
            String password,
            @Schema(example = "Ana Gómez")
            @NotBlank @Size(max = 120) String fullName,
            @Schema(example = "ACME S.A.S.", description = "Organization created for the new user, who becomes its ADMIN")
            @NotBlank @Size(max = 120) String organizationName) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + "]";
        }
    }

    public record LoginRequest(
            @Schema(example = "ana@acme.com") @NotBlank @Size(max = 254) String email,
            @Schema(example = "S3cure-Passw0rd!") @NotBlank @Size(max = 72) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    public record RefreshTokenRequest(@NotBlank @Size(max = 100) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshTokenRequest[***]";
        }
    }

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            @Schema(example = "Bearer") String tokenType,
            @Schema(description = "Access token lifetime in seconds", example = "900") long expiresIn,
            UserResponse user) {
    }
}
