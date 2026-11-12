package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Resultado de un rechazo: el estado en que quedó el expediente y el acta que lo respalda. */
public record DecisionResult(UUID playerId, PlayerStatus playerStatus, UUID minuteId, String minuteNumber) {
}
