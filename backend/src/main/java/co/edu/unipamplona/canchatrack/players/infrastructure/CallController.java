package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.CallAdministrationService;
import co.edu.unipamplona.canchatrack.players.application.CallQueryService;
import co.edu.unipamplona.canchatrack.players.application.CreateCallCommand;
import co.edu.unipamplona.canchatrack.players.application.GrantExceptionCommand;
import co.edu.unipamplona.canchatrack.players.application.UpdateCallCommand;
import co.edu.unipamplona.canchatrack.players.domain.CallStatus;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CallDetailResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CallExceptionResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CallResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CancelCallRequest;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CreateCallRequest;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.GrantExceptionRequest;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.UpdateCallRequest;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.V1 + "/calls")
public class CallController {

    private final CallQueryService queries;
    private final CallAdministrationService administration;

    public CallController(CallQueryService queries, CallAdministrationService administration) {
        this.queries = queries;
        this.administration = administration;
    }

    /** Convocatorias abiertas ahora mismo (cualquier usuario autenticado). */
    @GetMapping("/open")
    public List<CallResponse> open() {
        return queries.listOpen().stream().map(CallResponse::from).toList();
    }

    // --- Administración: solo el coordinador (DAD CU-01, RF-043, RF-044) ---

    @PostMapping
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public CallDetailResponse create(@Valid @RequestBody CreateCallRequest request, HttpServletRequest http) {
        return CallDetailResponse.from(administration.create(RequestOrigins.adminActor(http),
                new CreateCallCommand(request.seasonId(), request.name(), request.startAt(), request.endAt(),
                        request.instructions())));
    }

    @GetMapping
    @PreAuthorize("hasRole('COORDINATOR')")
    public List<CallDetailResponse> list(@RequestParam(required = false) UUID seasonId,
                                         @RequestParam(required = false) CallStatus status) {
        return administration.list(seasonId, status).stream().map(CallDetailResponse::from).toList();
    }

    @GetMapping("/{callId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public CallDetailResponse get(@PathVariable UUID callId) {
        return CallDetailResponse.from(administration.get(callId));
    }

    @PatchMapping("/{callId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public CallDetailResponse update(@PathVariable UUID callId, @Valid @RequestBody UpdateCallRequest request,
                                     HttpServletRequest http) {
        return CallDetailResponse.from(administration.update(RequestOrigins.adminActor(http), callId,
                new UpdateCallCommand(request.name(), request.startAt(), request.endAt(), request.instructions(),
                        request.reason())));
    }

    @PostMapping("/{callId}/publish")
    @PreAuthorize("hasRole('COORDINATOR')")
    public CallDetailResponse publish(@PathVariable UUID callId, HttpServletRequest http) {
        return CallDetailResponse.from(administration.publish(RequestOrigins.adminActor(http), callId));
    }

    @PostMapping("/{callId}/cancel")
    @PreAuthorize("hasRole('COORDINATOR')")
    public CallDetailResponse cancel(@PathVariable UUID callId, @Valid @RequestBody CancelCallRequest request,
                                     HttpServletRequest http) {
        return CallDetailResponse.from(administration.cancel(RequestOrigins.adminActor(http), callId, request.reason()));
    }

    @PostMapping("/{callId}/exceptions")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public CallExceptionResponse grantException(@PathVariable UUID callId,
                                                @Valid @RequestBody GrantExceptionRequest request,
                                                HttpServletRequest http) {
        return CallExceptionResponse.from(administration.grantException(RequestOrigins.adminActor(http), callId,
                new GrantExceptionCommand(request.guardianId(), request.reason(), request.expiresAt())));
    }

    @GetMapping("/{callId}/exceptions")
    @PreAuthorize("hasRole('COORDINATOR')")
    public List<CallExceptionResponse> exceptions(@PathVariable UUID callId) {
        return administration.exceptions(callId).stream().map(CallExceptionResponse::from).toList();
    }
}
