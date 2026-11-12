package co.edu.unipamplona.canchatrack.audit.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de la bitácora tal como se consulta. {@code beforeJson} y {@code afterJson} son los resúmenes seguros que escribió
 * cada operación (sin binarios, secretos ni datos de salud). No incluye IP ni agente de usuario: esos datos técnicos quedan
 * en la tabla para investigación, pero no se publican por la API (política pendiente, DDS 12.3).
 */
public record AuditEntry(UUID id, Instant occurredAt, UUID actorId, String actorRole, String action, String entityType,
                         UUID entityId, String outcome, String beforeJson, String afterJson, String reason,
                         String correlationId) {
}
