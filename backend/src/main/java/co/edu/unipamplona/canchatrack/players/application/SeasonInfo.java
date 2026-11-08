package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.SeasonStatus;

public record SeasonInfo(UUID id, String name, Instant startAt, Instant endAt, SeasonStatus status, Instant createdAt) {
}
