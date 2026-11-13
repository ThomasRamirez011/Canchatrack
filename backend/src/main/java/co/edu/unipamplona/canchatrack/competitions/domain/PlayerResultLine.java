package co.edu.unipamplona.canchatrack.competitions.domain;

import java.util.UUID;

/** Participación de un jugador convocado en el partido (RF-036). Minutos y puntos son opcionales. */
public record PlayerResultLine(UUID playerId, Integer minutesPlayed, Integer goals, String observations) {
}
