package com.documind.organization;

import com.documind.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    public record OrganizationResponse(UUID id, String name, Instant createdAt) {
    }

    public record CreateMemberRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72, message = "must have between 12 and 72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "must contain letters and digits")
            String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotNull Role role) {

        @Override
        public String toString() {
            return "CreateMemberRequest[email=" + email + ", role=" + role + "]";
        }
    }
}
