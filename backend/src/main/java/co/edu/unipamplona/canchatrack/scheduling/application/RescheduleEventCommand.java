package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Nueva programación de un evento confirmado. {@code venueId} y {@code headCoachId} nulos conservan los actuales;
 * {@code meetingPoint} e {@code instructions} nulos también. La hora de concentración no se arrastra: depende de la fecha, así que
 * solo existe si se indica de nuevo.
 */
public record RescheduleEventCommand(UUID venueId, UUID headCoachId, Instant startAt, Instant endAt, Instant meetingAt,
                                     String meetingPoint, String instructions, String reason) {
}
