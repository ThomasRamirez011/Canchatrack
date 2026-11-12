package co.edu.unipamplona.canchatrack.audit.application;

import java.time.Instant;
import java.util.UUID;

/** Parámetros crudos de la consulta; el servicio los normaliza y valida. */
public record AuditSearch(String action, String entityType, UUID entityId, UUID actorId, String outcome, Instant from,
                          Instant to, Integer limit, String cursor) {
}
