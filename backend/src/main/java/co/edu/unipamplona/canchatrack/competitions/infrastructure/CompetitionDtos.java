package co.edu.unipamplona.canchatrack.competitions.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.competitions.application.Views.CallupView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.MatchDetail;
import co.edu.unipamplona.canchatrack.competitions.application.Views.MatchReschedule;
import co.edu.unipamplona.canchatrack.competitions.application.Views.MatchView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.PlayerMatchView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.TournamentView;

/** Contratos JSON de torneos, partidos y convocatorias. Las solicitudes no llevan Bean Validation: las reglas viven en el dominio. */
final class CompetitionDtos {

    private CompetitionDtos() {
    }

    record CreateTournamentRequest(UUID seasonId, String name, LocalDate startDate, LocalDate endDate, UUID categoryId,
                                   UUID venueId, String rules) {
    }

    record TournamentResponse(UUID id, UUID seasonId, String name, LocalDate startDate, LocalDate endDate, UUID categoryId,
                              UUID venueId, String rules) {

        static TournamentResponse from(TournamentView t) {
            return new TournamentResponse(t.id(), t.seasonId(), t.name(), t.startDate(), t.endDate(), t.categoryId(),
                    t.venueId(), t.rules());
        }
    }

    record ScheduleMatchRequest(UUID tournamentId, UUID categoryId, UUID venueId, UUID headCoachId, String opponent,
                                Instant startAt, Instant endAt, Instant meetingAt, String meetingPoint, String instructions) {
    }

    record CancelMatchRequest(String reason) {
    }

    record RescheduleMatchRequest(UUID venueId, UUID headCoachId, Instant startAt, Instant endAt, Instant meetingAt,
                                  String meetingPoint, String instructions, String reason) {
    }

    record CallupReviewResponse(UUID playerId, List<String> causes) {
    }

    record RescheduleMatchResponse(MatchResponse match, List<CallupReviewResponse> confirmedToReview) {

        static RescheduleMatchResponse from(MatchReschedule r) {
            return new RescheduleMatchResponse(MatchResponse.from(r.match()),
                    r.confirmedToReview().stream().map(c -> new CallupReviewResponse(c.playerId(), c.causes())).toList());
        }
    }

    record MatchResponse(UUID id, UUID eventId, UUID tournamentId, String tournamentName, UUID categoryId, UUID venueId,
                         String venueName, UUID headCoachId, String opponent, Instant startAt, Instant endAt,
                         Instant meetingAt, String meetingPoint, String instructions, String status,
                         String cancellationReason, Integer scoreFor, Integer scoreAgainst, String resultNotes,
                         Instant resultRecordedAt) {

        static MatchResponse from(MatchView m) {
            return new MatchResponse(m.id(), m.eventId(), m.tournamentId(), m.tournamentName(), m.categoryId(), m.venueId(),
                    m.venueName(), m.headCoachId(), m.opponent(), m.startAt(), m.endAt(), m.meetingAt(), m.meetingPoint(),
                    m.instructions(), m.status(), m.cancellationReason(), m.scoreFor(), m.scoreAgainst(), m.resultNotes(),
                    m.resultRecordedAt());
        }
    }

    record CallupPlayersRequest(List<UUID> playerIds) {
    }

    /** {@code eligibility} e {@code ineligibilityCode} son del personal; el acudiente nunca recibe este contrato. */
    record CallupResponse(UUID playerId, String playerCode, String firstName, String lastName, String status,
                          String ineligibilityCode, Map<String, Boolean> eligibility, Instant proposedAt,
                          Instant confirmedAt, boolean authorized, Integer minutesPlayed, Integer goals,
                          String observations) {

        static CallupResponse from(CallupView c) {
            return new CallupResponse(c.playerId(), c.playerCode(), c.firstName(), c.lastName(), c.status(),
                    c.ineligibilityCode(), c.eligibility(), c.proposedAt(), c.confirmedAt(), c.authorized(),
                    c.minutesPlayed(), c.goals(), c.observations());
        }
    }

    record MatchDetailResponse(MatchResponse match, List<CallupResponse> callups) {

        static MatchDetailResponse from(MatchDetail d) {
            return new MatchDetailResponse(MatchResponse.from(d.match()), d.callups().stream().map(CallupResponse::from).toList());
        }
    }

    record PlayerResultRequest(UUID playerId, Integer minutesPlayed, Integer goals, String observations) {
    }

    record MatchResultRequest(Integer scoreFor, Integer scoreAgainst, String resultNotes, String reason,
                              List<PlayerResultRequest> players) {
    }

    record MatchAuthorizationResponse(UUID matchId, UUID playerId, boolean recordedNow) {
    }

    record PlayerMatchResponse(UUID matchId, String opponent, String tournamentName, Instant startAt, String matchStatus,
                               boolean authorized, Integer minutesPlayed, Integer goals, Integer scoreFor,
                               Integer scoreAgainst) {

        static PlayerMatchResponse from(PlayerMatchView v) {
            return new PlayerMatchResponse(v.matchId(), v.opponent(), v.tournamentName(), v.startAt(), v.matchStatus(),
                    v.authorized(), v.minutesPlayed(), v.goals(), v.scoreFor(), v.scoreAgainst());
        }
    }
}
