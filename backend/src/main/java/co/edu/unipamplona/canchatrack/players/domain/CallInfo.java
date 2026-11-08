package co.edu.unipamplona.canchatrack.players.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Convocatoria de inscripciones. {@code windowOpen} lo calcula la base de datos con su propio reloj (RN-01): la hora
 * de la aplicación nunca decide si una convocatoria está abierta.
 */
public record CallInfo(UUID id, String name, CallStatus status, Instant startAt, Instant endAt, String instructions,
                       boolean windowOpen) {
}
