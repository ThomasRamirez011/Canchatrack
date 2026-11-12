package co.edu.unipamplona.canchatrack.audit.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Posición de paginación por claves (keyset): el último evento entregado. Es opaca para el cliente. Paginar por
 * {@code (occurred_at, id)} mantiene páginas estables aunque entren eventos nuevos, y su costo no crece con la página.
 */
public record AuditCursor(Instant occurredAt, UUID id) {

    private static final String SEPARATOR = "|";

    public String encode() {
        String raw = occurredAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException si el texto no es un cursor emitido por {@link #encode()} */
    public static AuditCursor parse(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = raw.indexOf(SEPARATOR);
            if (separator <= 0) {
                throw new IllegalArgumentException("malformed cursor");
            }
            return new AuditCursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed cursor", e);
        }
    }
}
