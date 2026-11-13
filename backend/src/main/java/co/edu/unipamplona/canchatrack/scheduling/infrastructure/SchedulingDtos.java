package co.edu.unipamplona.canchatrack.scheduling.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.scheduling.application.EventView;
import co.edu.unipamplona.canchatrack.scheduling.application.SeriesInfo;
import co.edu.unipamplona.canchatrack.scheduling.application.SeriesView;
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

    record RescheduleEventRequest(UUID venueId, UUID headCoachId, Instant startAt, Instant endAt, Instant meetingAt,
                                  String meetingPoint, String instructions, String reason) {
    }

    record EventResponse(UUID id, String eventType, UUID categoryId, UUID venueId, String venueName, UUID headCoachId,
                         Instant startAt, Instant endAt, Instant meetingAt, String meetingPoint, String instructions,
                         String status, String cancellationReason) {

        static EventResponse from(EventView e) {
            return new EventResponse(e.id(), e.eventType(), e.categoryId(), e.venueId(), e.venueName(), e.headCoachId(),
                    e.startAt(), e.endAt(), e.meetingAt(), e.meetingPoint(), e.instructions(), e.status(), e.cancellationReason());
        }
    }

    /** Serie semanal: {@code weekdays} en MONDAY..SUNDAY; las horas son locales del club. */
    record ScheduleSeriesRequest(String eventType, UUID categoryId, UUID venueId, UUID headCoachId, List<String> weekdays,
                                 LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
                                 String meetingPoint, String instructions) {
    }

    record SeriesResponse(UUID id, String eventType, UUID categoryId, UUID venueId, UUID headCoachId, List<String> weekdays,
                          LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime, String timeZone,
                          String meetingPoint, String instructions, int occurrenceCount, List<EventResponse> events) {

        static SeriesResponse from(SeriesView view) {
            SeriesInfo s = view.series();
            return new SeriesResponse(s.id(), s.eventType(), s.categoryId(), s.venueId(), s.headCoachId(),
                    s.weekdays().stream().map(Enum::name).toList(), s.startDate(), s.endDate(), s.startTime(), s.endTime(),
                    s.timeZone(), s.meetingPoint(), s.instructions(), s.occurrenceCount(),
                    view.events().stream().map(EventResponse::from).toList());
        }
    }
}
