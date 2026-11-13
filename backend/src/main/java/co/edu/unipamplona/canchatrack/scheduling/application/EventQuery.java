package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Filtros del calendario (RF-013). Todos son opcionales y se combinan con Y; {@code from} es inclusivo y {@code to} exclusivo.
 * El calendario mensual, semanal o diario se obtiene con el rango de fechas.
 */
public record EventQuery(UUID categoryId, UUID venueId, UUID headCoachId, String eventType, String status, UUID seriesId,
                         Instant from, Instant to) {

    public static EventQuery all() {
        return new EventQuery(null, null, null, null, null, null, null, null);
    }

    public EventQuery withHeadCoach(UUID coachId) {
        return new EventQuery(categoryId, venueId, coachId, eventType, status, seriesId, from, to);
    }
}
