package co.edu.unipamplona.canchatrack.audit.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.audit.application.AuditEntry;
import co.edu.unipamplona.canchatrack.audit.application.AuditPage;

/** Contratos JSON de la consulta de auditoría (ver openapi/canchatrack-v1.yaml). */
final class AuditDtos {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditDtos() {
    }

    record AuditEventResponse(UUID id, Instant occurredAt, UUID actorId, String actorRole, String action,
                              String entityType, UUID entityId, String outcome, JsonNode before, JsonNode after,
                              String reason, String correlationId) {

        static AuditEventResponse from(AuditEntry e) {
            return new AuditEventResponse(e.id(), e.occurredAt(), e.actorId(), e.actorRole(), e.action(), e.entityType(),
                    e.entityId(), e.outcome(), parse(e.beforeJson()), parse(e.afterJson()), e.reason(), e.correlationId());
        }
    }

    record AuditEventPageResponse(List<AuditEventResponse> items, String nextCursor) {

        static AuditEventPageResponse from(AuditPage page) {
            return new AuditEventPageResponse(page.items().stream().map(AuditEventResponse::from).toList(), page.nextCursor());
        }
    }

    private static JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
