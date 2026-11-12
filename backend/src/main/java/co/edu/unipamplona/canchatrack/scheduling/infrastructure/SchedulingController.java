package co.edu.unipamplona.canchatrack.scheduling.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.scheduling.application.CreateVenueCommand;
import co.edu.unipamplona.canchatrack.scheduling.application.RescheduleEventCommand;
import co.edu.unipamplona.canchatrack.scheduling.application.ScheduleEventCommand;
import co.edu.unipamplona.canchatrack.scheduling.application.SchedulingActor;
import co.edu.unipamplona.canchatrack.scheduling.application.SchedulingService;
import co.edu.unipamplona.canchatrack.scheduling.infrastructure.SchedulingDtos.CancelEventRequest;
import co.edu.unipamplona.canchatrack.scheduling.infrastructure.SchedulingDtos.CreateVenueRequest;
import co.edu.unipamplona.canchatrack.scheduling.infrastructure.SchedulingDtos.EventResponse;
import co.edu.unipamplona.canchatrack.scheduling.infrastructure.SchedulingDtos.RescheduleEventRequest;
import co.edu.unipamplona.canchatrack.scheduling.infrastructure.SchedulingDtos.ScheduleEventRequest;
import co.edu.unipamplona.canchatrack.scheduling.infrastructure.SchedulingDtos.VenueResponse;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Escenarios y agenda de eventos (DDS UC-04/05; RN-14). */
@RestController
@RequestMapping(ApiPaths.V1)
public class SchedulingController {

    private final SchedulingService scheduling;

    public SchedulingController(SchedulingService scheduling) {
        this.scheduling = scheduling;
    }

    @PostMapping("/venues")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public VenueResponse createVenue(@RequestBody CreateVenueRequest request, HttpServletRequest http) {
        return VenueResponse.from(scheduling.createVenue(actor(http),
                new CreateVenueCommand(request.name(), request.address(), request.reference())));
    }

    @GetMapping("/venues")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'COACH')")
    public List<VenueResponse> venues(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return scheduling.venues(includeInactive).stream().map(VenueResponse::from).toList();
    }

    @PostMapping("/events")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse schedule(@RequestBody ScheduleEventRequest request, HttpServletRequest http) {
        return EventResponse.from(scheduling.schedule(actor(http), new ScheduleEventCommand(request.eventType(),
                request.categoryId(), request.venueId(), request.headCoachId(), request.startAt(), request.endAt(),
                request.meetingAt(), request.meetingPoint(), request.instructions())));
    }

    /** Coordinador y comité, todos; el entrenador, solo los suyos. */
    @GetMapping("/events")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'COACH')")
    public List<EventResponse> events(@RequestParam(required = false) UUID categoryId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        AuthenticatedUser user = CurrentUser.require();
        return scheduling.list(user.id(), user.roles(), categoryId,
                from == null ? null : from.toInstant(), to == null ? null : to.toInstant()).stream().map(EventResponse::from).toList();
    }

    @GetMapping("/events/{eventId}")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'COACH')")
    public EventResponse event(@PathVariable UUID eventId) {
        AuthenticatedUser user = CurrentUser.require();
        return EventResponse.from(scheduling.get(user.id(), user.roles(), eventId));
    }

    @PostMapping("/events/{eventId}/cancel")
    @PreAuthorize("hasRole('COORDINATOR')")
    public EventResponse cancel(@PathVariable UUID eventId, @RequestBody CancelEventRequest request, HttpServletRequest http) {
        return EventResponse.from(scheduling.cancel(actor(http), eventId, request.reason()));
    }

    /** Cambia fecha, lugar o entrenador de un entrenamiento o evaluación confirmado y avisa a las familias y al entrenador. */
    @PostMapping("/events/{eventId}/reschedule")
    @PreAuthorize("hasRole('COORDINATOR')")
    public EventResponse reschedule(@PathVariable UUID eventId, @RequestBody RescheduleEventRequest request, HttpServletRequest http) {
        return EventResponse.from(scheduling.reschedule(actor(http), eventId, new RescheduleEventCommand(request.venueId(),
                request.headCoachId(), request.startAt(), request.endAt(), request.meetingAt(), request.meetingPoint(),
                request.instructions(), request.reason())));
    }

    private static SchedulingActor actor(HttpServletRequest request) {
        return new SchedulingActor(CurrentUser.require().id(), remoteAddress(request), request.getHeader("User-Agent"));
    }

    private static InetAddress remoteAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(remote);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
