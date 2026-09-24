package com.documind.auth;

import com.documind.user.Role;
import com.documind.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Principal de Spring Security. Lleva lo que las reglas de autorización necesitan (ID, organización
 * y rol) para no tener que volver a cargar el usuario en cada comprobación.
 */
public record AuthenticatedUser(UUID id, UUID organizationId, String email, Role role, String passwordHash,
                                boolean enabled) implements UserDetails {

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getOrganizationId(), user.getEmail(), user.getRole(),
                user.getPasswordHash(), user.isEnabled());
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        // Nunca se imprime el hash de la contraseña
        return "AuthenticatedUser[id=" + id + ", role=" + role + "]";
    }
}
