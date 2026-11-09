package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** {@code chargeId} solo se informa al iniciar la matrícula. */
public record RegistrationResult(UUID playerId, PlayerStatus playerStatus, UUID chargeId) {
}
