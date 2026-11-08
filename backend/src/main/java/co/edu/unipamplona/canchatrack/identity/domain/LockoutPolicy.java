package co.edu.unipamplona.canchatrack.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Bloqueo temporal por intentos fallidos consecutivos (SEC: mitigación de fuerza bruta). Al alcanzar el máximo la
 * cuenta se bloquea {@code lockDuration} y el contador vuelve a cero, de modo que al vencer el bloqueo se empieza de nuevo.
 */
public record LockoutPolicy(int maxAttempts, Duration lockDuration) {

    public LockoutPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("lockDuration must be positive");
        }
    }

    /** Estado resultante a persistir tras un intento fallido. */
    public record State(int failedAttempts, Instant lockedUntil) {

        public boolean locked() {
            return lockedUntil != null;
        }
    }

    public State afterFailure(UserAccount account, Instant now) {
        int attempts = account.failedLoginAttempts() + 1;
        if (attempts >= maxAttempts) {
            return new State(0, now.plus(lockDuration));
        }
        return new State(attempts, null);
    }
}
