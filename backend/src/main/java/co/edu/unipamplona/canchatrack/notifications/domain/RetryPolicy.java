package co.edu.unipamplona.canchatrack.notifications.domain;

import java.time.Duration;
import java.util.Optional;

/**
 * Política de reintentos de envío: espera exponencial (factor 4) entre intentos, con tope, y un máximo de intentos. Es una
 * función pura: el procesador decide con ella cuánto esperar y cuándo rendirse (el aviso queda {@code FAILED}).
 *
 * @param maxAttempts intentos totales, incluido el primero (mínimo 1)
 * @param baseDelay   espera tras el primer fallo
 * @param maxDelay    tope de la espera entre intentos
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    private static final int FACTOR = 4;

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (baseDelay == null || baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be shorter than baseDelay");
        }
    }

    /**
     * Espera antes del siguiente intento tras fallar el intento {@code attemptNumber} (1 es el primero). Vacío cuando ya no
     * quedan intentos: el fallo es definitivo.
     */
    public Optional<Duration> delayAfterFailure(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber starts at 1");
        }
        if (attemptNumber >= maxAttempts) {
            return Optional.empty();
        }
        Duration delay = baseDelay;
        for (int i = 1; i < attemptNumber; i++) {
            delay = delay.multipliedBy(FACTOR);
            if (delay.compareTo(maxDelay) >= 0) {
                return Optional.of(maxDelay);
            }
        }
        return Optional.of(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
    }
}
