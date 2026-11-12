package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo notifications ({@code canchatrack.notifications.*}).
 *
 * @param sender    {@code log} (simulado, solo desarrollo y pruebas) o {@code smtp} (producción)
 * @param from      remitente de los correos; obligatorio con {@code smtp}
 * @param processor ritmo del procesador de la cola
 * @param retry     política de reintentos
 */
@ConfigurationProperties(prefix = "canchatrack.notifications")
public record NotificationProperties(
        @DefaultValue("log") String sender,
        String from,
        @DefaultValue Processor processor,
        @DefaultValue Retry retry) {

    /**
     * @param enabled   apaga el procesador (las pruebas lo apagan y lo invocan a mano)
     * @param interval  espera entre pasadas
     * @param batchSize avisos por pasada
     * @param lease     arriendo de un aviso reclamado; debe superar lo que tarda un envío
     */
    public record Processor(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10s") Duration interval,
            @DefaultValue("50") int batchSize,
            @DefaultValue("5m") Duration lease) {
    }

    /**
     * @param maxAttempts intentos totales por aviso
     * @param baseDelay   espera tras el primer fallo (crece x4 en cada intento)
     * @param maxDelay    tope de la espera
     */
    public record Retry(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("1m") Duration baseDelay,
            @DefaultValue("6h") Duration maxDelay) {
    }
}
