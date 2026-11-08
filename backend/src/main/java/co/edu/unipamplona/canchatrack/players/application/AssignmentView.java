package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Categoría, entrenador titular y panel vigentes de un jugador, con el acta que los respalda. */
public record AssignmentView(UUID playerId, PlayerStatus playerStatus, UUID categoryId, String categoryName,
                             UUID headCoachId, List<UUID> evaluatorIds, String minuteNumber, LocalDate meetingDate,
                             UUID minuteFileId, String capacityExceptionReason, Instant assignedAt) {
}
