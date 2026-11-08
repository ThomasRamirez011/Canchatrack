package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Expediente mínimo de un jugador, tal como lo necesita la radicación. */
public record PlayerRecord(UUID id, String code, PlayerStatus status) {
}
