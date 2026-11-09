package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;
import java.util.UUID;

/** Un evento existente que se cruza con el solicitado; {@code dimension}: VENUE, COACH o CATEGORY. */
public record ConflictView(String dimension, UUID eventId, Instant startAt, Instant endAt) {
}
