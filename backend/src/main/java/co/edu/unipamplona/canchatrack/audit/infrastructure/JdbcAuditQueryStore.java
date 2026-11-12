package co.edu.unipamplona.canchatrack.audit.infrastructure;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.audit.application.AuditCursor;
import co.edu.unipamplona.canchatrack.audit.application.AuditEntry;
import co.edu.unipamplona.canchatrack.audit.application.AuditFilter;
import co.edu.unipamplona.canchatrack.audit.application.AuditQueryStore;

/**
 * Lectura de {@code audit_events} con filtros opcionales. El SQL se arma solo con fragmentos fijos; todo valor del
 * usuario viaja como parámetro. El orden {@code (occurred_at DESC, id DESC)} lo cubren {@code ix_audit_events_occurred}
 * (V16) y los índices por entidad, actor y acción de V3.
 */
@Repository
public class JdbcAuditQueryStore implements AuditQueryStore {

    private static final String SELECT = """
            SELECT id, occurred_at, actor_id, actor_role, action, entity_type, entity_id, outcome,
                   before_state::text AS before_state, after_state::text AS after_state, reason, correlation_id
            FROM audit_events
            WHERE true
            """;

    private final JdbcTemplate jdbc;

    public JdbcAuditQueryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AuditEntry> search(AuditFilter filter, AuditCursor after, int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> args = new ArrayList<>();
        if (filter.action() != null) {
            sql.append(" AND action = ?");
            args.add(filter.action());
        }
        if (filter.entityType() != null) {
            sql.append(" AND entity_type = ?");
            args.add(filter.entityType());
        }
        if (filter.entityId() != null) {
            sql.append(" AND entity_id = ?");
            args.add(filter.entityId());
        }
        if (filter.actorId() != null) {
            sql.append(" AND actor_id = ?");
            args.add(filter.actorId());
        }
        if (filter.outcome() != null) {
            sql.append(" AND outcome = ?");
            args.add(filter.outcome());
        }
        if (filter.from() != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
        }
        if (filter.to() != null) {
            sql.append(" AND occurred_at < ?");
            args.add(OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
        }
        if (after != null) {
            sql.append(" AND (occurred_at, id) < (?::timestamptz, ?::uuid)");
            args.add(OffsetDateTime.ofInstant(after.occurredAt(), ZoneOffset.UTC));
            args.add(after.id());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(limit);

        return jdbc.query(sql.toString(), (rs, rowNum) -> new AuditEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_role"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getString("outcome"),
                rs.getString("before_state"),
                rs.getString("after_state"),
                rs.getString("reason"),
                rs.getString("correlation_id")), args.toArray());
    }
}
