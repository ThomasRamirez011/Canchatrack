package co.edu.unipamplona.canchatrack.scheduling.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Nueva programación del evento de un partido. {@code venueId}, {@code headCoachId}, {@code meetingPoint} e
 * {@code instructions} nulos conservan los actuales; la hora de concentración solo existe si se indica de nuevo.
 */
public record MatchEventChange(UUID venueId, UUID headCoachId, Instant startAt, Instant endAt, Instant meetingAt,
                               String meetingPoint, String instructions) {
}
