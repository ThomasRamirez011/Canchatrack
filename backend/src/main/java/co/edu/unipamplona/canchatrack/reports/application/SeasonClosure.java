package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Instant;
import java.util.UUID;

/** Cierre del expediente en una temporada (T-11) y, si ocurrió, su reapertura (T-12). */
public record SeasonClosure(UUID id, UUID playerId, UUID seasonId, UUID reportId, UUID closedBy, Instant closedAt,
                            UUID reopenedBy, Instant reopenedAt, String reopenReason, String reopenDetail) {

    public boolean open() {
        return reopenedAt == null;
    }
}
