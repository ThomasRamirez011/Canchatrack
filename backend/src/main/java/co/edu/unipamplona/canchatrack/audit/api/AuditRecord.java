package co.edu.unipamplona.canchatrack.audit.api;

import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;

/**
 * Evidencia de una operación sensible (estructura de DDS 12.3 / DAD 9.2).
 *
 * <p>{@code beforeJson} y {@code afterJson} son resúmenes JSON <b>seguros</b>: sin binarios, secretos, tokens ni
 * datos de salud. El {@code correlationId} lo completa el escritor desde la solicitud en curso.
 *
 * @param actorId    usuario que actúa; {@code null} para el sistema o intentos anónimos (login fallido)
 * @param actorRole  rol efectivo con el que actuó
 * @param action     código estable de la operación, p. ej. {@code PAYMENT_VOIDED} (máx. 80)
 * @param entityType tipo de objeto afectado, p. ej. {@code payment} (máx. 60)
 * @param entityId   identificador del objeto afectado
 * @param outcome    resultado de la operación
 * @param beforeJson estado anterior (JSON) o {@code null}
 * @param afterJson  estado posterior (JSON) o {@code null}
 * @param reason     motivo, obligatorio en cierre, reapertura, anulación, corrección y similares
 * @param ip         origen técnico de la solicitud
 * @param userAgent  agente de usuario (se trunca a 500 caracteres)
 */
public record AuditRecord(
        UUID actorId,
        String actorRole,
        String action,
        String entityType,
        UUID entityId,
        Outcome outcome,
        String beforeJson,
        String afterJson,
        String reason,
        InetAddress ip,
        String userAgent) {

    private static final int MAX_ACTION = 80;
    private static final int MAX_ENTITY_TYPE = 60;
    private static final int MAX_USER_AGENT = 500;

    public enum Outcome {
        SUCCESS, DENIED, FAILURE
    }

    public AuditRecord {
        action = requireText(action, "action", MAX_ACTION);
        entityType = requireText(entityType, "entityType", MAX_ENTITY_TYPE);
        Objects.requireNonNull(outcome, "outcome");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT);
        }
    }

    public static Builder builder(String action, String entityType) {
        return new Builder(action, entityType);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    public static final class Builder {

        private final String action;
        private final String entityType;
        private UUID actorId;
        private String actorRole;
        private UUID entityId;
        private Outcome outcome = Outcome.SUCCESS;
        private String beforeJson;
        private String afterJson;
        private String reason;
        private InetAddress ip;
        private String userAgent;

        private Builder(String action, String entityType) {
            this.action = action;
            this.entityType = entityType;
        }

        public Builder actor(UUID actorId, String actorRole) {
            this.actorId = actorId;
            this.actorRole = actorRole;
            return this;
        }

        public Builder entity(UUID entityId) {
            this.entityId = entityId;
            return this;
        }

        public Builder outcome(Outcome outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder before(String json) {
            this.beforeJson = json;
            return this;
        }

        public Builder after(String json) {
            this.afterJson = json;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder origin(InetAddress ip, String userAgent) {
            this.ip = ip;
            this.userAgent = userAgent;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(actorId, actorRole, action, entityType, entityId, outcome,
                    beforeJson, afterJson, reason, ip, userAgent);
        }
    }
}
