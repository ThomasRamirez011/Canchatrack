package co.edu.unipamplona.canchatrack.competitions.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.competitions.application.Views.CallupRow;
import co.edu.unipamplona.canchatrack.competitions.application.Views.MatchView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.PlayerMatchView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.TournamentView;
import co.edu.unipamplona.canchatrack.competitions.domain.PlayerResultLine;

/**
 * Puerto de salida de torneos, partidos y convocatorias. Las escrituras exigen una transacción activa. Las lecturas de
 * partidos incluyen datos del evento y del escenario como proyección de solo lectura: el evento solo se escribe a través
 * de {@code scheduling.api}.
 */
public interface CompetitionStore {

    /** Reloj de la base de datos. */
    Instant now();

    /** @throws DuplicateTournamentException si la temporada ya tiene un torneo con ese nombre
     *  @throws ReferenceNotFoundException si la temporada, la categoría o el escenario no existen */
    UUID insertTournament(UUID seasonId, String name, LocalDate startDate, LocalDate endDate,
                          UUID categoryId, UUID venueId, String rules, UUID createdBy);

    Optional<TournamentView> findTournament(UUID tournamentId);

    List<TournamentView> findTournaments(UUID seasonId);

    UUID insertMatch(UUID eventId, UUID tournamentId, String opponent, UUID createdBy);

    Optional<MatchView> findMatch(UUID matchId);

    /** Bloquea la fila del partido hasta el fin de la transacción: serializa convocatoria, confirmación y resultado. */
    Optional<MatchView> lockMatch(UUID matchId);

    /** @param coachId si no es nulo, solo los partidos de los que es entrenador responsable */
    List<MatchView> findMatches(UUID categoryId, UUID tournamentId, Instant from, Instant to, UUID coachId, int limit);

    List<CallupRow> callupsOf(UUID matchId);

    /** Jugadores con convocatoria confirmada en el partido que corresponde a ese evento de agenda; vacío si no hay partido. */
    List<UUID> confirmedPlayersOfEvent(UUID eventId);

    /** Filas existentes para esos jugadores en el partido, por jugador. */
    Map<UUID, CallupRow> callupsOf(UUID matchId, Collection<UUID> playerIds);

    /**
     * Crea la propuesta o la sobrescribe. No debe llamarse para filas CONFIRMED: quien llama las descarta antes.
     *
     * @throws ReferenceNotFoundException si el jugador no existe
     */
    void upsertProposal(UUID matchId, UUID playerId, String status, Map<String, Boolean> eligibility,
                        String ineligibilityCode, UUID proposedBy);

    void markRemoved(UUID matchId, UUID playerId);

    /** PROPOSED → CONFIRMED con el detalle de elegibilidad evaluado al confirmar; la fecha la pone la base de datos. */
    void confirm(UUID matchId, UUID playerId, Map<String, Boolean> eligibility, UUID confirmedBy);

    void saveResult(UUID matchId, int scoreFor, int scoreAgainst, String notes, UUID recordedBy);

    void saveParticipation(UUID matchId, PlayerResultLine line);

    /** Partidos en los que el jugador tiene convocatoria confirmada, del más reciente al más antiguo. */
    List<PlayerMatchView> historyOf(UUID playerId);
}
