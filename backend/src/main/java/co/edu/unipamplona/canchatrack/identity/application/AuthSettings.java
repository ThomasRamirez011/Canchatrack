package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Duration;
import java.util.Objects;

import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;

/**
 * Parámetros de sesión.
 *
 * @param refreshTtl         vigencia de cada refresh token (se renueva al rotar)
 * @param refreshAbsoluteTtl tope de toda la sesión desde el inicio de sesión; no se extiende al rotar
 */
public record AuthSettings(Duration refreshTtl, Duration refreshAbsoluteTtl, LockoutPolicy lockout) {

    public AuthSettings {
        Objects.requireNonNull(lockout, "lockout");
        requirePositive(refreshTtl, "refreshTtl");
        requirePositive(refreshAbsoluteTtl, "refreshAbsoluteTtl");
        if (refreshAbsoluteTtl.compareTo(refreshTtl) < 0) {
            throw new IllegalArgumentException("refreshAbsoluteTtl must be >= refreshTtl");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
