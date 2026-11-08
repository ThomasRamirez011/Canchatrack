package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.CallStatus;

/** Vista administrativa de una convocatoria. {@code windowOpen} lo calcula la base con su reloj (RN-01). */
public record CallDetail(UUID id, UUID seasonId, String name, Instant startAt, Instant endAt, String instructions,
                         CallStatus status, boolean windowOpen, Instant createdAt, Instant updatedAt) {
}
