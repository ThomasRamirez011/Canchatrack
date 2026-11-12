package co.edu.unipamplona.canchatrack.competitions.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.competitions.domain.PlayerResultLine;

/** Comandos de entrada de los casos de uso. Sin validación: las reglas viven en el dominio. */
public final class CompetitionCommands {

    private CompetitionCommands() {
    }

    public record CreateTournament(UUID seasonId, String name, LocalDate startDate, LocalDate endDate, UUID categoryId,
                                   UUID venueId, String rules) {
    }

    public record ScheduleMatch(UUID tournamentId, UUID categoryId, UUID venueId, UUID headCoachId, String opponent,
                                Instant startAt, Instant endAt, Instant meetingAt, String meetingPoint, String instructions) {
    }

    public record RecordResult(Integer scoreFor, Integer scoreAgainst, String resultNotes, String reason,
                               List<PlayerResultLine> players) {
    }
}
