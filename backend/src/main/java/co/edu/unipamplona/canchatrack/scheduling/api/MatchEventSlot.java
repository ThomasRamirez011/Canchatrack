package co.edu.unipamplona.canchatrack.scheduling.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Datos de agenda de un partido. {@code eventType}: FRIENDLY_MATCH o TOURNAMENT_MATCH. Las reglas de horario, escenario,
 * entrenador y cruces (RN-14) son las mismas de cualquier evento.
 */
public record MatchEventSlot(String eventType, UUID categoryId, UUID venueId, UUID headCoachId, Instant startAt,
                             Instant endAt, Instant meetingAt, String meetingPoint, String instructions) {
}
