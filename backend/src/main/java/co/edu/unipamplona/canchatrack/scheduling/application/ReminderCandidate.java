package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;

/** Evento confirmado próximo a empezar y el momento en que se programó. */
public record ReminderCandidate(EventView event, Instant createdAt) {
}
