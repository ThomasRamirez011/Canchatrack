package co.edu.unipamplona.canchatrack.shared.security;

import java.util.Set;
import java.util.UUID;

/** Usuario autenticado de la solicitud en curso, tal como lo declara el access token. */
public record AuthenticatedUser(UUID id, Set<String> roles) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(String roleCode) {
        return roles.contains(roleCode);
    }
}
