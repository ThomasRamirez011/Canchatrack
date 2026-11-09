package co.edu.unipamplona.canchatrack.scheduling.infrastructure;

import java.time.Instant;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.scheduling.application.EventView;
import co.edu.unipamplona.canchatrack.scheduling.application.VenueInfo;

/** Contratos JSON de escenarios y eventos. Las solicitudes no llevan Bean Validation: las reglas viven en el dominio. */
final class SchedulingDtos {

    private SchedulingDtos() {
    }

    record CreateVenueRequest(String name, String address, String reference) {
    }

    record VenueResponse(UUID id, String name, String address, String reference, boolean active) {

        static VenueResponse from(VenueInfo v) {
            return new VenueResponse(v.id(), v.name(), v.address(), v.reference(), v.active());
        }
    }

    record ScheduleEventRequest(String eventType, UUID categoryId, UUID venueId, UUID headCoachId, Instant startAt,
                                Instant endAt, Instant meetingAt, String meetingPoint, String instructions) {
    }

    record CancelEventRequest(String reason) {
    }

    record EventResponse(UUID id, String eventType, UUID categoryId, UUID venueId, String venueName, UUID headCoachId,
                         Instant startAt, Instant endAt, Instant meetingAt, String meetingPoint, String instructions,
                         String status, String cancellationReason) {

        static EventResponse from(EventView e) {
            return new EventResponse(e.id(), e.eventType(), e.categoryId(), e.venueId(), e.venueName(), e.headCoachId(),
                    e.startAt(), e.endAt(), e.meetingAt(), e.meetingPoint(), e.instructions(), e.status(), e.cancellationReason());
        }
    }
}
