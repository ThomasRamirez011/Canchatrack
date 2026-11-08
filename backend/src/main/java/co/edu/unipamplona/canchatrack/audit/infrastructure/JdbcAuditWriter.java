package co.edu.unipamplona.canchatrack.audit.infrastructure;

import java.net.InetAddress;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.shared.web.CorrelationIds;

/**
 * Adaptador JDBC de {@link AuditWriter}: inserta en {@code audit_events}. La tabla es append-only
 * (triggers de V3), así que esta clase solo inserta.
 */
@Component
public class JdbcAuditWriter implements AuditWriter {

    private static final String INSERT_SQL = """
            INSERT INTO audit_events (id, occurred_at, actor_id, actor_role, action, entity_type, entity_id, outcome,
                                      before_state, after_state, reason, ip, user_agent, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::inet, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcAuditWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AuditRecord record) {
        insert(record);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeIndependent(AuditRecord record) {
        insert(record);
    }

    private void insert(AuditRecord record) {
        jdbc.update(INSERT_SQL,
                UUID.randomUUID(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                record.actorId(),
                record.actorRole(),
                record.action(),
                record.entityType(),
                record.entityId(),
                record.outcome().name(),
                record.beforeJson(),
                record.afterJson(),
                record.reason(),
                hostAddress(record.ip()),
                record.userAgent(),
                CorrelationIds.current());
    }

    /** {@code inet} no admite el identificador de zona IPv6 ("fe80::1%eth0"). */
    private static String hostAddress(InetAddress ip) {
        if (ip == null) {
            return null;
        }
        String address = ip.getHostAddress();
        int zone = address.indexOf('%');
        return zone >= 0 ? address.substring(0, zone) : address;
    }
}
