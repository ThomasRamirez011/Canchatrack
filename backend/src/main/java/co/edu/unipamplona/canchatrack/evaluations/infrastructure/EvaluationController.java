package co.edu.unipamplona.canchatrack.evaluations.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.ChangePeriod;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.CloseRound;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.Decide;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.OpenRound;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.Promote;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationService;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationsActor;
import co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos.ChangePeriodRequest;
import co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos.CloseRoundRequest;
import co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos.DecisionRequest;
import co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos.DecisionResponse;
import co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos.OpenRoundRequest;
import co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos.PromoteRequest;
import co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos.PromotionResponse;
import co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos.RoundResponse;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Rondas de evaluación, conceptos de los evaluadores y ascenso de categoría (DDS UC-06/07; RN-08..RN-12). */
@RestController
@RequestMapping(ApiPaths.V1)
public class EvaluationController {

    private static final List<String> DECIDER_ROLES = List.of("EVALUATOR", "COACH", "COMMITTEE");

    private final EvaluationService evaluations;

    public EvaluationController(EvaluationService evaluations) {
        this.evaluations = evaluations;
    }

    @PostMapping("/players/{playerId}/evaluation-rounds")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoundResponse open(@PathVariable UUID playerId, @RequestBody OpenRoundRequest request, HttpServletRequest http) {
        return RoundResponse.from(evaluations.open(actor(http, EvaluationsActor.COORDINATOR), playerId,
                new OpenRound(request.periodStart(), request.periodEnd(), request.dueAt())));
    }

    @GetMapping("/players/{playerId}/evaluation-rounds")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'EVALUATOR', 'COACH')")
    public List<RoundResponse> rounds(@PathVariable UUID playerId) {
        AuthenticatedUser user = CurrentUser.require();
        return evaluations.list(user.id(), user.roles(), playerId).stream().map(RoundResponse::from).toList();
    }

    @GetMapping("/evaluation-rounds/{roundId}")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'EVALUATOR', 'COACH')")
    public RoundResponse round(@PathVariable UUID roundId) {
        AuthenticatedUser user = CurrentUser.require();
        return RoundResponse.from(evaluations.get(user.id(), user.roles(), roundId));
    }

    /** El evaluador emite o ajusta su propio concepto; nadie, ni la coordinación, decide en nombre de otro (RN-08, RN-10). */
    @PutMapping("/evaluation-rounds/{roundId}/decision")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'COACH', 'COMMITTEE')")
    public DecisionResponse decide(@PathVariable UUID roundId, @RequestBody DecisionRequest request, HttpServletRequest http) {
        Set<String> roles = CurrentUser.require().roles();
        String role = DECIDER_ROLES.stream().filter(roles::contains).findFirst().orElse("EVALUATOR");
        return DecisionResponse.from(evaluations.decide(actor(http, role), roundId,
                new Decide(request.verdict(), request.justification(), request.actions(), request.criteria())));
    }

    @PutMapping("/evaluation-rounds/{roundId}/period")
    @PreAuthorize("hasRole('COORDINATOR')")
    public RoundResponse changePeriod(@PathVariable UUID roundId, @RequestBody ChangePeriodRequest request, HttpServletRequest http) {
        return RoundResponse.from(evaluations.changePeriod(actor(http, EvaluationsActor.COORDINATOR), roundId,
                new ChangePeriod(request.periodStart(), request.periodEnd(), request.dueAt(), request.reason())));
    }

    @PostMapping("/evaluation-rounds/{roundId}/close")
    @PreAuthorize("hasRole('COORDINATOR')")
    public RoundResponse close(@PathVariable UUID roundId, @RequestBody(required = false) CloseRoundRequest request,
                               HttpServletRequest http) {
        return RoundResponse.from(evaluations.close(actor(http, EvaluationsActor.COORDINATOR), roundId,
                new CloseRound(request == null ? null : request.followUpPlan())));
    }

    @PostMapping("/players/{playerId}/promotion")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PromotionResponse promote(@PathVariable UUID playerId, @RequestBody PromoteRequest request, HttpServletRequest http) {
        return PromotionResponse.from(evaluations.promote(actor(http, EvaluationsActor.COORDINATOR), playerId,
                new Promote(request.targetCategoryId(), request.headCoachId())));
    }

    private static EvaluationsActor actor(HttpServletRequest request, String role) {
        return new EvaluationsActor(CurrentUser.require().id(), role, remoteAddress(request), request.getHeader("User-Agent"));
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
