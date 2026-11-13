package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.api.StaffDirectory;
import co.edu.unipamplona.canchatrack.scheduling.domain.FieldError;
import co.edu.unipamplona.canchatrack.scheduling.domain.RecurrenceRules;
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
    static final String ACTION_RESCHEDULED = "EVENT_RESCHEDULED";
    static final String ACTION_SERIES_SCHEDULED = "EVENT_SERIES_SCHEDULED";
    static final String ACTION_SERIES_CANCELLED = "EVENT_SERIES_CANCELLED";
    static final int MAX_LIST = 500;

    private final SchedulingStore store;
    private final StaffDirectory staff;
    private final AuditWriter audit;
    private final AgendaNotices notices;
    private final SeriesSettings seriesSettings;

    public SchedulingService(SchedulingStore store, StaffDirectory staff, AuditWriter audit, AgendaNotices notices,
                             SeriesSettings seriesSettings) {
        this.store = store;
        this.staff = staff;
        this.audit = audit;
        this.notices = notices;
        this.seriesSettings = seriesSettings;
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
        EventView event = scheduleOfTypes(actor, command, SchedulingRules.EVENT_TYPES);
        notices.scheduled(event); // RF-016: acudientes y entrenador; en la misma transacción, el procesador los envía
        return event;
    }

    /**
     * Programa un evento de alguno de los tipos admitidos. Es público (y no de paquete) porque el proxy transaccional de
     * Spring solo intercepta de forma fiable los métodos públicos; los partidos entran por {@link MatchEventService}.
     */
    @Transactional
    public EventView scheduleOfTypes(SchedulingActor actor, ScheduleEventCommand command, Set<String> allowedTypes) {
        Instant now = store.now();
        List<FieldError> errors = new ArrayList<>(SchedulingRules.eventViolations(allowedTypes, command.eventType(),
                command.startAt(), command.endAt(), command.meetingAt(), command.meetingPoint(), command.instructions(), now));
        errors.addAll(referenceErrors(command.categoryId(), command.venueId(), command.headCoachId()));
        ValidationFailure.throwIfAny(errors);

        store.lockSlots(command.venueId(), command.headCoachId(), command.categoryId());
        List<ConflictView> conflicts = store.findConflicts(command.venueId(), command.headCoachId(), command.categoryId(),
                command.startAt(), command.endAt(), null);
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

    /** La categoría, el escenario activo y el entrenador (cuenta activa con rol COACH) son obligatorios al programar. */
    private List<FieldError> referenceErrors(UUID categoryId, UUID venueId, UUID headCoachId) {
        List<FieldError> errors = new ArrayList<>();
        if (categoryId == null) {
            errors.add(new FieldError("categoryId", "La categoría es obligatoria."));
        }
        VenueInfo venue = venueId == null ? null : store.findVenue(venueId).orElse(null);
        if (venueId == null) {
            errors.add(new FieldError("venueId", "El escenario es obligatorio."));
        } else if (venue == null) {
            errors.add(new FieldError("venueId", "El escenario no existe."));
        } else if (!venue.active()) {
            errors.add(new FieldError("venueId", "El escenario no está activo."));
        }
        if (headCoachId == null) {
            errors.add(new FieldError("headCoachId", "El entrenador responsable es obligatorio."));
        } else {
            Set<String> roles = staff.activeRolesOf(List.of(headCoachId)).get(headCoachId);
            if (roles == null || !roles.contains("COACH")) {
                errors.add(new FieldError("headCoachId", "El entrenador debe ser una cuenta activa con rol COACH."));
            }
        }
        return errors;
    }

    /**
     * Programa una serie semanal (RF-014): crea una sesión por cada fecha que cae en los días indicados. Es todo o nada: si
     * alguna cruza el horario de su escenario, entrenador o categoría (RN-14) no se crea ninguna y se responde 409 con todos los
     * cruces. Un solo aviso por correo, con una invitación de calendario de todas las sesiones, llega a acudientes y entrenador.
     */
    @Transactional
    public SeriesView scheduleSeries(SchedulingActor actor, ScheduleSeriesCommand command) {
        Instant now = store.now();
        ZoneId zone = seriesSettings.zone();
        LocalDate today = now.atZone(zone).toLocalDate();
        String type = command.eventType() == null ? null : command.eventType().trim().toUpperCase(Locale.ROOT);

        List<FieldError> errors = new ArrayList<>();
        if (type == null || !SchedulingRules.EVENT_TYPES.contains(type)) {
            errors.add(new FieldError("eventType", "El tipo debe ser uno de: "
                    + String.join(", ", SchedulingRules.EVENT_TYPES.stream().sorted().toList()) + "."));
        }
        Set<DayOfWeek> weekdays = parseWeekdays(command.weekdays(), errors);
        List<FieldError> recurrence = RecurrenceRules.violations(weekdays, command.startDate(), command.endDate(),
                command.startTime(), command.endTime(), today, zone);
        errors.addAll(recurrence);
        List<RecurrenceRules.Slot> slots = recurrence.isEmpty() && errors.stream().noneMatch(e -> "weekdays".equals(e.field()))
                ? RecurrenceRules.expand(weekdays, command.startDate(), command.endDate(), command.startTime(),
                command.endTime(), zone)
                : List.of();
        if (!slots.isEmpty() && !slots.get(0).startAt().isAfter(now)) {
            errors.add(new FieldError("startTime", "La primera sesión debe ser a futuro."));
        }
        errors.addAll(SchedulingRules.detailViolations(command.meetingPoint(), command.instructions()));
        errors.addAll(referenceErrors(command.categoryId(), command.venueId(), command.headCoachId()));
        ValidationFailure.throwIfAny(errors);

        store.lockSlots(command.venueId(), command.headCoachId(), command.categoryId());
        List<Map<String, String>> conflicts = new ArrayList<>();
        for (RecurrenceRules.Slot slot : slots) {
            for (ConflictView c : store.findConflicts(command.venueId(), command.headCoachId(), command.categoryId(),
                    slot.startAt(), slot.endAt(), null)) {
                conflicts.add(Map.of("occurrenceStartAt", slot.startAt().toString(), "dimension", c.dimension(),
                        "eventId", c.eventId().toString(), "startAt", c.startAt().toString(), "endAt", c.endAt().toString()));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new DomainException(ErrorCode.SCHEDULE_CONFLICT,
                    "Hay sesiones de la serie que se cruzan con otros eventos; no se creó ninguna.", Map.of("conflicts", conflicts));
        }

        UUID seriesId;
        try {
            seriesId = store.insertSeries(type, command.categoryId(), command.venueId(), command.headCoachId(), weekdays,
                    command.startDate(), command.endDate(), command.startTime(), command.endTime(), zone.getId(),
                    blankToNull(command.meetingPoint()), blankToNull(command.instructions()), slots.size(), actor.id());
            for (RecurrenceRules.Slot slot : slots) {
                store.insertSeriesEvent(seriesId, slot.position(), type, command.categoryId(), command.venueId(), slot.startAt(),
                        slot.endAt(), blankToNull(command.meetingPoint()), blankToNull(command.instructions()),
                        command.headCoachId(), actor.id());
            }
        } catch (ReferenceNotFoundException e) {
            throw ValidationFailure.of("categoryId", "La categoría no existe.");
        }
        SeriesView view = new SeriesView(store.findSeries(seriesId).orElseThrow(), store.findSeriesEvents(seriesId));
        audit.write(AuditRecord.builder(ACTION_SERIES_SCHEDULED, "event_series")
                .actor(actor.id(), SchedulingActor.ROLE)
                .entity(seriesId)
                .after(Json.of("type", type, "categoryId", command.categoryId(), "venueId", command.venueId(),
                        "headCoachId", command.headCoachId(), "weekdays", view.series().weekdays().toString(),
                        "startDate", command.startDate(), "endDate", command.endDate(), "occurrences", slots.size()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        notices.seriesScheduled(view);
        return view;
    }

    /**
     * Cancela las sesiones futuras y confirmadas de una serie con un solo motivo y un solo aviso (RF-017, RF-018). Las que ya
     * empezaron, se completaron o se cancelaron antes no se tocan.
     */
    @Transactional
    public SeriesView cancelSeries(SchedulingActor actor, UUID seriesId, String reason) {
        ValidationFailure.throwIfAny(SchedulingRules.cancellationViolations(reason));
        SeriesInfo series = store.findSeries(seriesId).orElseThrow(SchedulingService::seriesNotFound);
        Instant now = store.now();
        List<EventView> open = store.findSeriesEvents(seriesId).stream()
                .filter(e -> "CONFIRMED".equals(e.status()) && e.startAt().isAfter(now)).toList();
        if (open.isEmpty()) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "La serie no tiene sesiones futuras confirmadas.");
        }
        List<EventView> cancelled = new ArrayList<>();
        for (EventView event : open) {
            cancelled.add(cancelEvent(actor, event.id(), reason));
        }
        audit.write(AuditRecord.builder(ACTION_SERIES_CANCELLED, "event_series")
                .actor(actor.id(), SchedulingActor.ROLE)
                .entity(seriesId)
                .after(Json.of("cancelled", cancelled.size()))
                .reason(reason.trim())
                .origin(actor.ip(), actor.userAgent())
                .build());
        notices.seriesCancelled(series, cancelled, reason.trim());
        return new SeriesView(series, store.findSeriesEvents(seriesId));
    }

    /** Coordinador y comité ven cualquier serie; el entrenador, solo las suyas (sin acceso responde 404). */
    @Transactional(readOnly = true)
    public SeriesView getSeries(UUID userId, Set<String> roles, UUID seriesId) {
        SeriesInfo series = store.findSeries(seriesId).orElseThrow(SchedulingService::seriesNotFound);
        if (!seesAll(roles) && !userId.equals(series.headCoachId())) {
            throw seriesNotFound();
        }
        return new SeriesView(series, store.findSeriesEvents(seriesId));
    }

    /** Los días llegan como MONDAY..SUNDAY; uno desconocido es un error de campo, no un fallo de lectura del cuerpo. */
    private static Set<DayOfWeek> parseWeekdays(List<String> names, List<FieldError> errors) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (names == null) {
            return days;
        }
        for (String name : names) {
            try {
                days.add(DayOfWeek.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("weekdays", "Día no válido «" + name + "»: use MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY o SUNDAY."));
            }
        }
        return days;
    }

    private static DomainException seriesNotFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "La serie no existe.");
    }

    /** Reprograma un entrenamiento o una evaluación y avisa del cambio (RF-017, RF-018). */
    @Transactional
    public EventView reschedule(SchedulingActor actor, UUID eventId, RescheduleEventCommand command) {
        Rescheduled change = rescheduleOfTypes(actor, eventId, command, SchedulingRules.EVENT_TYPES);
        notices.rescheduled(change.before(), change.after(), command.reason().trim());
        return change.after();
    }

    /**
     * Reprograma un evento confirmado de alguno de los tipos admitidos: valida como al programar, comprueba los cruces sin
     * contar el propio evento y sube su revisión. Es público por el proxy transaccional; los partidos entran por
     * {@link MatchEventService}.
     */
    @Transactional
    public Rescheduled rescheduleOfTypes(SchedulingActor actor, UUID eventId, RescheduleEventCommand command, Set<String> allowedTypes) {
        String status = store.lockStatus(eventId).orElseThrow(SchedulingService::notFound);
        EventView before = store.findEvent(eventId).orElseThrow(SchedulingService::notFound);
        if (!allowedTypes.contains(before.eventType())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, SchedulingRules.MATCH_EVENT_TYPES.contains(before.eventType())
                    ? "Los partidos se reprograman desde su propio recurso (/matches/{id}/reschedule) para avisar a los acudientes."
                    : "Este tipo de evento no se reprograma desde aquí.");
        }
        if (!"CONFIRMED".equals(status)) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "Solo un evento confirmado puede reprogramarse.");
        }
        Instant now = store.now();
        if (!before.startAt().isAfter(now)) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "Un evento que ya empezó no puede reprogramarse.");
        }

        UUID venueId = command.venueId() == null ? before.venueId() : command.venueId();
        UUID coachId = command.headCoachId() == null ? before.headCoachId() : command.headCoachId();
        String meetingPoint = command.meetingPoint() == null ? before.meetingPoint() : blankToNull(command.meetingPoint());
        String instructions = command.instructions() == null ? before.instructions() : blankToNull(command.instructions());

        List<FieldError> errors = new ArrayList<>(SchedulingRules.eventViolations(allowedTypes, before.eventType(),
                command.startAt(), command.endAt(), command.meetingAt(), meetingPoint, instructions, now));
        errors.addAll(SchedulingRules.rescheduleReasonViolations(command.reason()));
        VenueInfo venue = store.findVenue(venueId).orElse(null);
        if (venue == null) {
            errors.add(new FieldError("venueId", "El escenario no existe."));
        } else if (!venue.active() && !venueId.equals(before.venueId())) {
            errors.add(new FieldError("venueId", "El escenario no está activo."));
        }
        if (coachId != null && !coachId.equals(before.headCoachId())) {
            Set<String> roles = staff.activeRolesOf(List.of(coachId)).get(coachId);
            if (roles == null || !roles.contains("COACH")) {
                errors.add(new FieldError("headCoachId", "El entrenador debe ser una cuenta activa con rol COACH."));
            }
        }
        if (errors.isEmpty() && command.startAt().equals(before.startAt()) && command.endAt().equals(before.endAt())
                && venueId.equals(before.venueId()) && Objects.equals(coachId, before.headCoachId())
                && Objects.equals(command.meetingAt(), before.meetingAt())
                && Objects.equals(meetingPoint, before.meetingPoint())
                && Objects.equals(instructions, before.instructions())) {
            errors.add(new FieldError("startAt", "La reprogramación no cambia nada del evento."));
        }
        ValidationFailure.throwIfAny(errors);

        store.lockSlots(venueId, coachId, before.categoryId());
        List<ConflictView> conflicts = store.findConflicts(venueId, coachId, before.categoryId(), command.startAt(), command.endAt(), eventId);
        if (!conflicts.isEmpty()) {
            throw new DomainException(ErrorCode.SCHEDULE_CONFLICT, "El horario se cruza con otros eventos.",
                    Map.of("conflicts", conflicts.stream().map(c -> Map.of("dimension", c.dimension(),
                            "eventId", c.eventId().toString(), "startAt", c.startAt().toString(),
                            "endAt", c.endAt().toString())).toList()));
        }
        store.reschedule(eventId, venueId, coachId, command.startAt(), command.endAt(), command.meetingAt(), meetingPoint, instructions);
        EventView after = store.findEvent(eventId).orElseThrow();
        audit.write(AuditRecord.builder(ACTION_RESCHEDULED, "sports_event")
                .actor(actor.id(), SchedulingActor.ROLE)
                .entity(eventId)
                .before(Json.of("venueId", before.venueId(), "headCoachId", before.headCoachId(), "startAt", before.startAt(),
                        "endAt", before.endAt()))
                .after(Json.of("venueId", after.venueId(), "headCoachId", after.headCoachId(), "startAt", after.startAt(),
                        "endAt", after.endAt()))
                .reason(command.reason().trim())
                .origin(actor.ip(), actor.userAgent())
                .build());
        return new Rescheduled(before, after);
    }

    @Transactional
    public EventView cancel(SchedulingActor actor, UUID eventId, String reason) {
        EventView current = store.findEvent(eventId).orElseThrow(SchedulingService::notFound);
        if (SchedulingRules.MATCH_EVENT_TYPES.contains(current.eventType())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "Los partidos se cancelan desde su propio recurso (/matches/{id}/cancel) para avisar a los acudientes.");
        }
        EventView cancelled = cancelEvent(actor, eventId, reason);
        notices.cancelled(cancelled); // RF-018
        return cancelled;
    }

    /** Cancela cualquier evento confirmado con motivo; {@link #cancel} lo usa tras descartar los partidos. */
    @Transactional
    public EventView cancelEvent(SchedulingActor actor, UUID eventId, String reason) {
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

    /**
     * Calendario filtrable (RF-013). El entrenador solo ve sus eventos: si pide los de otro entrenador, el resultado es vacío.
     */
    @Transactional(readOnly = true)
    public List<EventView> list(UUID userId, Set<String> roles, EventQuery query) {
        String type = query.eventType() == null ? null : query.eventType().trim().toUpperCase(Locale.ROOT);
        String status = query.status() == null ? null : query.status().trim().toUpperCase(Locale.ROOT);
        ValidationFailure.throwIfAny(SchedulingRules.queryViolations(type, status, query.from(), query.to()));
        EventQuery effective = new EventQuery(query.categoryId(), query.venueId(), query.headCoachId(), type, status,
                query.seriesId(), query.from(), query.to());
        if (!seesAll(roles)) {
            if (effective.headCoachId() != null && !effective.headCoachId().equals(userId)) {
                return List.of();
            }
            effective = effective.withHeadCoach(userId);
        }
        return store.findEvents(effective, MAX_LIST);
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
