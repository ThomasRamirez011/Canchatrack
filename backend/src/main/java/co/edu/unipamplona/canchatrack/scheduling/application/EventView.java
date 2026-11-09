package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;
import java.util.UUID;

public record EventView(UUID id, String eventType, UUID categoryId, UUID venueId, String venueName, UUID headCoachId,
                        Instant startAt, Instant endAt, Instant meetingAt, String meetingPoint, String instructions,
                        String status, String cancellationReason) {
}
