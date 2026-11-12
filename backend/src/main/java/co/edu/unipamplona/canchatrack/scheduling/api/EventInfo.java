package co.edu.unipamplona.canchatrack.scheduling.api;

import java.time.Instant;
import java.util.UUID;

/** {@code status}: CONFIRMED, CANCELLED o COMPLETED. {@code headCoachId} es el entrenador responsable del evento. */
public record EventInfo(UUID id, String eventType, UUID categoryId, UUID venueId, Instant startAt, Instant endAt,
                        String status, UUID headCoachId) {
}
