package co.edu.unipamplona.canchatrack.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cuenta de usuario con los roles vigentes en el momento de cargarla.
 *
 * @param passwordHash   hash BCrypt; {@code null} si la cuenta usa solo OIDC (DT-06): no puede entrar con contraseña
 * @param lockedUntil    bloqueo temporal por intentos fallidos; {@code null} si no está bloqueada
 * @param emailVerifiedAt momento en que se confirmó el correo; {@code null} si aún no se confirma
 */
public record UserAccount(
        UUID id,
        String email,
        String fullName,
        String passwordHash,
        AccountStatus status,
        int failedLoginAttempts,
        Instant lockedUntil,
        Set<RoleCode> roles,
        Instant emailVerifiedAt) {

    public UserAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(status, "status");
        roles = Set.copyOf(roles);
    }

    /** {@code true} mientras el bloqueo temporal por intentos fallidos siga vigente. */
    public boolean isTemporarilyLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /** Puede iniciar sesión con contraseña: activa, con contraseña y sin bloqueo vigente. */
    public boolean canAuthenticate(Instant now) {
        return status == AccountStatus.ACTIVE && passwordHash != null && !isTemporarilyLockedAt(now);
    }
}
