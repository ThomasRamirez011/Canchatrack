package co.edu.unipamplona.canchatrack.competitions.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import co.edu.unipamplona.canchatrack.competitions.application.CompetitionCommands.CreateTournament;
import co.edu.unipamplona.canchatrack.competitions.application.CompetitionCommands.RecordResult;
import co.edu.unipamplona.canchatrack.competitions.application.CompetitionCommands.ScheduleMatch;
import co.edu.unipamplona.canchatrack.competitions.application.CompetitionService;
import co.edu.unipamplona.canchatrack.competitions.application.CompetitionsActor;
import co.edu.unipamplona.canchatrack.competitions.domain.PlayerResultLine;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.CallupPlayersRequest;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.CallupResponse;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.CancelMatchRequest;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.CreateTournamentRequest;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.MatchAuthorizationResponse;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.MatchDetailResponse;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.MatchResponse;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.MatchResultRequest;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.PlayerMatchResponse;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.ScheduleMatchRequest;
import co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos.TournamentResponse;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;

/** Torneos, partidos, convocatorias, autorizaciones y resultados (DDS UC-07; RN-06, RN-07, RN-13). */
@RestController
@RequestMapping(ApiPaths.V1)
public class CompetitionController {

    private final CompetitionService competitions;

    public CompetitionController(CompetitionService competitions) {
        this.competitions = competitions;
    }

    // ---------- torneos ----------

    @PostMapping("/tournaments")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public TournamentResponse createTournament(@RequestBody CreateTournamentRequest request, HttpServletRequest http) {
        return TournamentResponse.from(competitions.createTournament(actor(http), new CreateTournament(request.seasonId(),
                request.name(), request.startDate(), request.endDate(), request.categoryId(), request.venueId(), request.rules())));
    }

    @GetMapping("/tournaments")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'COACH')")
    public List<TournamentResponse> tournaments(@RequestParam(required = false) UUID seasonId) {
        return competitions.tournaments(seasonId).stream().map(TournamentResponse::from).toList();
    }

    // ---------- partidos ----------

    @PostMapping("/matches")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public MatchResponse scheduleMatch(@RequestBody ScheduleMatchRequest request, HttpServletRequest http) {
        return MatchResponse.from(competitions.scheduleMatch(actor(http), new ScheduleMatch(request.tournamentId(),
                request.categoryId(), request.venueId(), request.headCoachId(), request.opponent(), request.startAt(),
                request.endAt(), request.meetingAt(), request.meetingPoint(), request.instructions())));
    }

    /** Coordinador y comité, todos; el entrenador, solo los suyos. */
    @GetMapping("/matches")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'COACH')")
    public List<MatchResponse> matches(@RequestParam(required = false) UUID categoryId,
                                       @RequestParam(required = false) UUID tournamentId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                       HttpServletRequest http) {
        return competitions.matches(actor(http), categoryId, tournamentId, from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant()).stream().map(MatchResponse::from).toList();
    }

    @GetMapping("/matches/{matchId}")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE', 'COACH')")
    public MatchDetailResponse match(@PathVariable UUID matchId, HttpServletRequest http) {
        return MatchDetailResponse.from(competitions.match(actor(http), matchId));
    }

    @PostMapping("/matches/{matchId}/cancel")
    @PreAuthorize("hasRole('COORDINATOR')")
    public MatchResponse cancel(@PathVariable UUID matchId, @RequestBody CancelMatchRequest request, HttpServletRequest http) {
        return MatchResponse.from(competitions.cancelMatch(actor(http), matchId, request.reason()));
    }

    // ---------- convocatoria ----------

    /** Propone jugadores: cada uno se evalúa y el que no cumple queda NOT_ELIGIBLE con su causa. */
    @PostMapping("/matches/{matchId}/callup")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COACH')")
    public List<CallupResponse> propose(@PathVariable UUID matchId, @RequestBody CallupPlayersRequest request, HttpServletRequest http) {
        return competitions.propose(actor(http), matchId, request.playerIds()).stream().map(CallupResponse::from).toList();
    }

    @DeleteMapping("/matches/{matchId}/callup/{playerId}")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COACH')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID matchId, @PathVariable UUID playerId, HttpServletRequest http) {
        competitions.remove(actor(http), matchId, playerId);
    }

    @PostMapping("/matches/{matchId}/callup/confirm")
    @PreAuthorize("hasRole('COORDINATOR')")
    public List<CallupResponse> confirm(@PathVariable UUID matchId, @RequestBody CallupPlayersRequest request, HttpServletRequest http) {
        return competitions.confirm(actor(http), matchId, request.playerIds()).stream().map(CallupResponse::from).toList();
    }

    /** El acudiente autoriza la participación o el desplazamiento de su hijo en el partido (RF-034). */
    @PostMapping("/matches/{matchId}/players/{playerId}/authorization")
    @PreAuthorize("hasRole('GUARDIAN')")
    public MatchAuthorizationResponse authorize(@PathVariable UUID matchId, @PathVariable UUID playerId, HttpServletRequest http) {
        var result = competitions.authorize(actor(http), matchId, playerId);
        return new MatchAuthorizationResponse(result.matchId(), result.playerId(), result.recordedNow());
    }

    // ---------- resultado e historial ----------

    @PutMapping("/matches/{matchId}/result")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COACH')")
    public MatchDetailResponse recordResult(@PathVariable UUID matchId, @RequestBody MatchResultRequest request, HttpServletRequest http) {
        List<PlayerResultLine> lines = request.players() == null ? List.of() : request.players().stream()
                .map(p -> p == null ? null : new PlayerResultLine(p.playerId(), p.minutesPlayed(), p.goals(), p.observations()))
                .toList();
        return MatchDetailResponse.from(competitions.recordResult(actor(http), matchId, new RecordResult(request.scoreFor(),
                request.scoreAgainst(), request.resultNotes(), request.reason(), lines)));
    }

    @GetMapping("/players/{playerId}/matches")
    @PreAuthorize("hasAnyRole('GUARDIAN', 'COORDINATOR', 'COMMITTEE')")
    public List<PlayerMatchResponse> history(@PathVariable UUID playerId, HttpServletRequest http) {
        return competitions.history(actor(http), playerId).stream().map(PlayerMatchResponse::from).toList();
    }

    private static CompetitionsActor actor(HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.require();
        return new CompetitionsActor(user.id(), user.roles(), remoteAddress(request), request.getHeader("User-Agent"));
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
