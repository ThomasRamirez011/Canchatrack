package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;

/**
 * Parámetros del segundo factor.
 *
 * @param enabled         si es {@code false} nadie debe segundo factor (desarrollo y pruebas)
 * @param requiredRoles   roles a los que se les exige; basta tener uno
 * @param issuer          nombre que la aplicación autenticadora muestra junto a la cuenta
 * @param challengeTtl    vigencia del desafío entre la contraseña y el código
 * @param window          intervalos de 30 s que se toleran a cada lado del actual
 * @param backupCodeCount cuántos códigos de respaldo se entregan
 */
public record MfaSettings(boolean enabled, Set<RoleCode> requiredRoles, String issuer, Duration challengeTtl,
                          int window, int backupCodeCount) {

    public MfaSettings {
        Objects.requireNonNull(requiredRoles, "requiredRoles");
        requiredRoles = Set.copyOf(requiredRoles);
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (challengeTtl == null || challengeTtl.isZero() || challengeTtl.isNegative()) {
            throw new IllegalArgumentException("challengeTtl must be positive");
        }
        if (window < 0 || window > 3) {
            throw new IllegalArgumentException("window must be between 0 and 3");
        }
        if (backupCodeCount < 1 || backupCodeCount > 20) {
            throw new IllegalArgumentException("backupCodeCount must be between 1 and 20");
        }
    }

    public static MfaSettings disabled() {
        return new MfaSettings(false, Set.of(), "CanchaTrack", Duration.ofMinutes(5), 1, 10);
    }
}
