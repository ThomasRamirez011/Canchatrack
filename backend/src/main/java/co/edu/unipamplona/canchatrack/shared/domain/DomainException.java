package co.edu.unipamplona.canchatrack.shared.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Error de negocio con código estable. El dominio y los casos de uso lo lanzan sin conocer HTTP;
 * la capa web lo traduce a {@code application/problem+json} (ver {@code ApiExceptionHandler}).
 *
 * <p>El {@code detail} y las {@code extensions} llegan al cliente: no deben incluir datos clínicos, secretos
 * ni información interna (DDS 8.2, RN-17).
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Map<String, Object> extensions;

    public DomainException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public DomainException(ErrorCode code, String detail, Map<String, Object> extensions) {
        super(detail == null || detail.isBlank() ? Objects.requireNonNull(code, "code").defaultDetail() : detail);
        this.code = Objects.requireNonNull(code, "code");
        this.extensions = Map.copyOf(extensions);
    }

    public ErrorCode getCode() {
        return code;
    }

    /** Propiedades adicionales seguras para incluir en la respuesta (por ejemplo jugador y fecha de vencimiento). */
    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
