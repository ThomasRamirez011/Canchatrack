package co.edu.unipamplona.canchatrack.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Tope de eventos dentro de una ventana deslizante. Función pura: no sabe de base de datos ni de reloj.
 *
 * @param max    eventos permitidos dentro de la ventana
 * @param window duración de la ventana
 */
public record RateLimitRule(int max, Duration window) {

    public static final int MAX_ALLOWED = 10_000;
    public static final Duration MIN_WINDOW = Duration.ofMinutes(1);
    public static final Duration MAX_WINDOW = Duration.ofHours(24);

    public RateLimitRule {
        if (max < 1 || max > MAX_ALLOWED) {
            throw new IllegalArgumentException("max must be between 1 and " + MAX_ALLOWED);
        }
        if (window == null || window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("window must be between 1 minute and 24 hours");
        }
    }

    /** Inicio de la ventana que termina en {@code now}. */
    public Instant windowStart(Instant now) {
        return now.minus(window);
    }

    /**
     * Segundos que faltan para que se libere un cupo: el evento más antiguo de la ventana deja de contar al cumplirse
     * su ventana. Mínimo 1 para que la espera sugerida nunca sea cero.
     *
     * @param inWindow instantes de los eventos que hoy cuentan, en cualquier orden
     */
    public long retryAfterSeconds(List<Instant> inWindow, Instant now) {
        Instant oldest = inWindow.stream().min(Instant::compareTo).orElse(now);
        long seconds = Duration.between(now, oldest.plus(window)).toSeconds() + 1;
        return Math.max(1, Math.min(seconds, window.toSeconds()));
    }

    /** {@code true} si con {@code count} eventos ya contados un evento más supera el tope. */
    public boolean isExceeded(int count) {
        return count >= max;
    }
}
