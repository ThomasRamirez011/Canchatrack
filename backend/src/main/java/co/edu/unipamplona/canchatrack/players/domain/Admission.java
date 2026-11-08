package co.edu.unipamplona.canchatrack.players.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Resultado de evaluar si una inscripción puede radicarse (RN-01, RF-044): dentro de la convocatoria vigente, o fuera
 * de ella con una habilitación excepcional vigente del coordinador.
 *
 * @param admitted    si se acepta la radicación
 * @param exceptionId habilitación excepcional usada; {@code null} si se radicó dentro de la convocatoria
 */
public record Admission(boolean admitted, UUID exceptionId) {

    private static final Admission CLOSED = new Admission(false, null);

    public static Admission decide(CallInfo call, Optional<UUID> activeExceptionId) {
        if (call.status() != CallStatus.PUBLISHED) {
            return CLOSED;
        }
        if (call.windowOpen()) {
            return new Admission(true, null);
        }
        return activeExceptionId.map(id -> new Admission(true, id)).orElse(CLOSED);
    }
}
