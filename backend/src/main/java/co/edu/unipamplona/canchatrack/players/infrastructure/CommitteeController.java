package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.AssignCategoryCommand;
import co.edu.unipamplona.canchatrack.players.application.CommitteeDecisionService;
import co.edu.unipamplona.canchatrack.players.application.MinuteData;
import co.edu.unipamplona.canchatrack.players.application.RejectEnrollmentCommand;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.AssignCategoryRequest;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.AssignmentResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.DecisionResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.RejectEnrollmentRequest;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Decisión del Comité Técnico sobre un jugador con inscripción radicada (DDS UC-02, T-01 y T-02). */
@RestController
@RequestMapping(ApiPaths.V1 + "/players/{playerId}")
public class CommitteeController {

    private final CommitteeDecisionService decisions;

    public CommitteeController(CommitteeDecisionService decisions) {
        this.decisions = decisions;
    }

    /** T-01: SUBMITTED → CATEGORY_ASSIGNED con categoría, entrenador titular, panel de tres evaluadores y acta. */
    @PostMapping("/assignments")
    @PreAuthorize("hasRole('COMMITTEE')")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse assign(@PathVariable UUID playerId, @RequestBody AssignCategoryRequest request,
                                     HttpServletRequest http) {
        return AssignmentResponse.from(decisions.assign(RequestOrigins.committeeActor(http), playerId,
                new AssignCategoryCommand(
                        new MinuteData(request.minuteNumber(), request.meetingDate(), request.attendees(), request.minuteFileId()),
                        request.categoryId(), request.headCoachId(), request.evaluatorIds(),
                        request.capacityExceptionReason())));
    }

    /** T-02: SUBMITTED → REJECTED con motivo y acta. */
    @PostMapping("/rejections")
    @PreAuthorize("hasRole('COMMITTEE')")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionResponse reject(@PathVariable UUID playerId, @RequestBody RejectEnrollmentRequest request,
                                   HttpServletRequest http) {
        return DecisionResponse.from(decisions.reject(RequestOrigins.committeeActor(http), playerId,
                new RejectEnrollmentCommand(
                        new MinuteData(request.minuteNumber(), request.meetingDate(), request.attendees(), request.minuteFileId()),
                        request.reason())));
    }

    /** Categoría, entrenador titular y panel vigentes. 404 si el jugador no tiene asignación vigente. */
    @GetMapping("/assignment")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public AssignmentResponse current(@PathVariable UUID playerId) {
        return AssignmentResponse.from(decisions.currentAssignment(playerId));
    }
}
