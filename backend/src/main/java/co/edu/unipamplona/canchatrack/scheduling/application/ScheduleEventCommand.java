package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;
import java.util.UUID;

public record ScheduleEventCommand(String eventType, UUID categoryId, UUID venueId, UUID headCoachId, Instant startAt,
                                   Instant endAt, Instant meetingAt, String meetingPoint, String instructions) {
}
