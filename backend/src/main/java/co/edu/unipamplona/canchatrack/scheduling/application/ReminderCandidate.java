package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;

/**
 * Evento confirmado próximo a empezar y el momento en que se fijó su fecha actual: la programación o, si se reprogramó, el
 * último cambio. Un evento confirmado solo se modifica al reprogramarlo, así que ese momento es su {@code updated_at}.
 */
public record ReminderCandidate(EventView event, Instant scheduledAt) {
}
