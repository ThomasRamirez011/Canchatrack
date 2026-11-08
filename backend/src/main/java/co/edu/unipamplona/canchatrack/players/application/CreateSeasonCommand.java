package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;

public record CreateSeasonCommand(String name, Instant startAt, Instant endAt) {
}
