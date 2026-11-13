package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Resultado de suspender o rehabilitar a un jugador. */
public record PlayerStatusChange(UUID playerId, PlayerStatus previousStatus, PlayerStatus status) {
}
