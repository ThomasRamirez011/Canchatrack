package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.UUID;

public record CreateCallCommand(UUID seasonId, String name, Instant startAt, Instant endAt, String instructions) {
}
