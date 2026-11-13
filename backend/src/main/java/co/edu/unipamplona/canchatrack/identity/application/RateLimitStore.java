package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;

import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;

/** Puerto de salida: contador de eventos por cubeta y sujeto con ventana deslizante. */
public interface RateLimitStore {

    /**
     * Cuenta los eventos de la ventana y, si todavía caben, registra uno más; todo de forma atómica. La operación se
     * confirma por su cuenta (transacción propia): el intento debe quedar contado aunque la solicitud que lo originó
     * termine con error y deshaga la suya.
     */
    Attempt tryRecord(RateLimitBucket bucket, String subjectHash, RateLimitRule rule, Instant now);

    /** Borra los eventos anteriores a {@code cutoff}. @return filas borradas */
    int deleteOlderThan(Instant cutoff);

    /**
     * @param allowed           {@code true} si el evento cupo y quedó registrado
     * @param retryAfterSeconds espera sugerida cuando no cupo; 0 si cupo
     */
    record Attempt(boolean allowed, long retryAfterSeconds) {
    }
}
