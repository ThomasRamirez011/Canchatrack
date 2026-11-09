package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.api.StaffDirectory;
import co.edu.unipamplona.canchatrack.scheduling.domain.FieldError;
import co.edu.unipamplona.canchatrack.scheduling.domain.SchedulingRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Escenarios y programación de eventos (CU-05). RN-14: no se confirma un evento que cruce el horario de su escenario, de
 * su entrenador o de su categoría (los jugadores pertenecen a una sola categoría). El cruce se detecta antes de guardar y
 * se informa con los eventos en conflicto; las solicitudes simultáneas se serializan con bloqueos por recurso.
 */
@Service
public class SchedulingService {

    static final String ACTION_VENUE = "VENUE_CREATED";
    static final String ACTION_SCHEDULED = "EVENT_SCHEDULED";
    static final String ACTION_CANCELLED = "EVENT_CANCELLED";
    static final int MAX_LIST = 200;

    private final SchedulingStore store;
    private final StaffDirectory staff;
    private final AuditWriter audit;

    public SchedulingService(SchedulingStore store, StaffDirectory staff, AuditWriter audit) {
        this.store = store;
        this.staff = staff;
        this.audit = audit;
    }

    @Transactional
    public VenueInfo createVenue(SchedulingActor actor, CreateVenueCommand command) {
        ValidationFailure.throwIfAny(SchedulingRules.venueViolations(command.name(), command.address()));
        VenueInfo venue;
        try {
            venue = store.insertVenue(command.name().trim(), blankToNull(command.address()), blankToNull(command.reference()), actor.id());
        } catch (DuplicateVenueException e) {
            throw ValidationFailure.of("name", "Ya existe un escenario con ese nombre.");
        }
        audit.write(AuditRecord.builder(ACTION_VENUE, "venue")
                .actor(actor.id(), SchedulingActor.ROLE)
                .entity(venue.id())
                .after(Json.of("name", venue.name()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return venue;
    }

    @Transactional(readOnly = true)
    public List<VenueInfo> venues(boolean includeInactive) {
        return store.findVenues(includeInactive);
    }

    @Transactional
    public EventView schedule(SchedulingActor actor, ScheduleEventCommand command) {
        Instant now = store.now();
        List<FieldError> errors = new ArrayList<>(SchedulingRules.eventViolations(command.eventType(), command.startAt(),
                command.endAt(), command.meetingAt(), command.meetingPoint(), command.instructions(), now));
        if (command.categoryId() == null) {
            errors.add(new FieldError("categoryId", "La categoría es obligatoria."));
        }
        VenueInfo venue = command.venueId() == null ? null : store.findVenue(command.venueId()).orElse(null);
        if (command.venueId() == null) {
            errors.add(new FieldError("venueId", "El escenario es obligatorio."));
        } else if (venue == null) {
            errors.add(new FieldError("venueId", "El escenario no existe."));
        } else if (!venue.active()) {
            errors.add(new FieldError("venueId", "El escenario no está activo."));
        }
        if (command.headCoachId() == null) {
            errors.add(new FieldError("headCoachId", "El entrenador responsable es obligatorio."));
        } else {
            Set<String> roles = staff.activeRolesOf(List.of(command.headCoachId())).get(command.headCoachId());
            if (roles == null || !roles.contains("COACH")) {
                errors.add(new FieldError("headCoachId", "El entrenador debe ser una cuenta activa con rol COACH."));
            }
        }
        ValidationFailure.throwIfAny(errors);

        store.lockSlots(command.venueId(), command.headCoachId(), command.categoryId());
        List<ConflictView> conflicts = store.findConflicts(command.venueId(), command.headCoachId(), command.categoryId(),
                command.startAt(), command.endAt());
        if (!conflicts.isEmpty()) {
            throw new DomainException(ErrorCode.SCHEDULE_CONFLICT, "El horario se cruza con otros eventos.",
                    Map.of("conflicts", conflicts.stream().map(c -> Map.of("dimension", c.dimension(),
                            "eventId", c.eventId().toString(), "startAt", c.startAt().toString(),
                            "endAt", c.endAt().toString())).toList()));
        }
        UUID id;
        try {
            id = store.insertEvent(command.eventType().trim().toUpperCase(), command.categoryId(), command.venueId(),
                    command.startAt(), command.endAt(), command.meetingAt(), blankToNull(command.meetingPoint()),
                    blankToNull(command.instructions()), command.headCoachId(), actor.id());
        } catch (ReferenceNotFoundException e) {
            throw ValidationFailure.of("categoryId", "La categoría no existe.");
        }
        EventView event = store.findEvent(id).orElseThrow();
        audit.write(AuditRecord.builder(ACTION_SCHEDULED, "sports_event")
                .actor(actor.id(), SchedulingActor.ROLE)
                .entity(id)
                .after(Json.of("type", event.eventType(), "categoryId", event.categoryId(), "venueId", event.venueId(),
                        "headCoachId", event.headCoachId(), "startAt", event.startAt(), "endAt", event.endAt()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return event;
    }

    @Transactional
    public EventView cancel(SchedulingActor actor, UUID eventId, String reason) {
        ValidationFailure.throwIfAny(SchedulingRules.cancellationViolations(reason));
        String status = store.lockStatus(eventId).orElseThrow(SchedulingService::notFound);
        if (!"CONFIRMED".equals(status)) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "Solo un evento confirmado puede cancelarse.");
        }
        store.cancel(eventId, reason.trim());
        EventView event = store.findEvent(eventId).orElseThrow();
        audit.write(AuditRecord.builder(ACTION_CANCELLED, "sports_event")
                .actor(actor.id(), SchedulingActor.ROLE)
                .entity(eventId)
                .before(Json.of("status", status))
                .after(Json.of("status", event.status()))
                .reason(reason.trim())
                .origin(actor.ip(), actor.userAgent())
                .build());
        return event;
    }

    /** Coordinador y comité ven cualquier evento; el entrenador, solo los suyos (sin acceso responde 404). */
    @Transactional(readOnly = true)
    public EventView get(UUID userId, Set<String> roles, UUID eventId) {
        EventView event = store.findEvent(eventId).orElseThrow(SchedulingService::notFound);
        if (!seesAll(roles) && !userId.equals(event.headCoachId())) {
            throw notFound();
        }
        return event;
    }

    @Transactional(readOnly = true)
    public List<EventView> list(UUID userId, Set<String> roles, UUID categoryId, Instant from, Instant to) {
        UUID coachFilter = seesAll(roles) ? null : userId;
        return store.findEvents(categoryId, from, to, coachFilter, MAX_LIST);
    }

    private static boolean seesAll(Set<String> roles) {
        return roles.contains("COORDINATOR") || roles.contains("COMMITTEE");
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El evento no existe.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
