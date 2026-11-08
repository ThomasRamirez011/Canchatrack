package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;

/** Modificación parcial: un campo {@code null} no se toca. Un texto de instrucciones vacío las borra. */
public record UpdateCallCommand(String name, Instant startAt, Instant endAt, String instructions, String reason) {
}
