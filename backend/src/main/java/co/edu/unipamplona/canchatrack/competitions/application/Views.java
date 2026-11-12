package co.edu.unipamplona.canchatrack.competitions.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Proyecciones de lectura que devuelve la capa de aplicación. */
public final class Views {

    private Views() {
    }

    public record TournamentView(UUID id, UUID seasonId, String name, LocalDate startDate, LocalDate endDate,
                                 UUID categoryId, UUID venueId, String rules) {
    }

    /** {@code status}: CONFIRMED, CANCELLED o COMPLETED (estado del evento deportivo). */
    public record MatchView(UUID id, UUID eventId, UUID tournamentId, String tournamentName, UUID categoryId, UUID venueId,
                            String venueName, UUID headCoachId, String opponent, Instant startAt, Instant endAt,
                            Instant meetingAt, String meetingPoint, String instructions, String status,
                            String cancellationReason, Integer scoreFor, Integer scoreAgainst, String resultNotes,
                            Instant resultRecordedAt) {
    }

    /** Fila de {@code callup_entries} tal como se guarda (sin nombres: esos los resuelve {@code players.api}). */
    public record CallupRow(UUID playerId, String status, String ineligibilityCode, Map<String, Boolean> eligibility,
                            Instant proposedAt, Instant confirmedAt, Integer minutesPlayed, Integer goals,
                            String observations) {
    }

    /** {@code eligibility} e {@code ineligibilityCode} solo se muestran al personal: nunca al acudiente (RN-17). */
    public record CallupView(UUID playerId, String playerCode, String firstName, String lastName, String status,
                             String ineligibilityCode, Map<String, Boolean> eligibility, Instant proposedAt,
                             Instant confirmedAt, boolean authorized, Integer minutesPlayed, Integer goals,
                             String observations) {
    }

    public record MatchDetail(MatchView match, List<CallupView> callups) {
    }

    /** Un partido en el historial deportivo del jugador (RF-037). */
    public record PlayerMatchView(UUID matchId, String opponent, String tournamentName, Instant startAt,
                                  String matchStatus, boolean authorized, Integer minutesPlayed, Integer goals,
                                  Integer scoreFor, Integer scoreAgainst) {
    }

    /** Jugador confirmado que, con la nueva fecha del partido, ya no cumple algún requisito (causas de elegibilidad). */
    public record CallupReview(UUID playerId, List<String> causes) {
    }

    /** El partido reprogramado y los confirmados que la coordinación debe revisar. */
    public record MatchReschedule(MatchView match, List<CallupReview> confirmedToReview) {
    }

    public record AuthorizationResult(UUID matchId, UUID playerId, boolean recordedNow) {
    }
}
