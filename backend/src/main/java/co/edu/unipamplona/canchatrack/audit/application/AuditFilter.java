package co.edu.unipamplona.canchatrack.audit.application;

import java.time.Instant;
import java.util.UUID;

/** Criterios ya normalizados y validados; cualquiera puede ser {@code null}. {@code from} inclusivo, {@code to} exclusivo. */
public record AuditFilter(String action, String entityType, UUID entityId, UUID actorId, String outcome, Instant from,
                          Instant to) {
}
