package co.edu.unipamplona.canchatrack.notifications.application;

import java.time.Duration;
import java.time.ZoneId;

import co.edu.unipamplona.canchatrack.notifications.domain.RetryPolicy;

/**
 * Parámetros del procesador.
 *
 * @param batchSize avisos por pasada
 * @param lease     arriendo de un aviso reclamado: debe superar lo que tarda un envío, o dos instancias podrían duplicarlo
 * @param zone      zona del club para mostrar fechas en el correo
 */
public record NotificationSettings(int batchSize, Duration lease, ZoneId zone, RetryPolicy retry) {

    public NotificationSettings {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        if (zone == null || retry == null) {
            throw new IllegalArgumentException("zone and retry are required");
        }
    }
}
