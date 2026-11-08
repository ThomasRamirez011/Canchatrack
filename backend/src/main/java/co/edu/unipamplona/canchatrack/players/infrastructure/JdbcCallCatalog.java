package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.players.application.CallCatalog;
import co.edu.unipamplona.canchatrack.players.domain.CallInfo;
import co.edu.unipamplona.canchatrack.players.domain.CallStatus;

/** Toda vigencia ("abierta", "habilitación vigente") se evalúa con {@code now()} de la base de datos (RN-01). */
@Repository
public class JdbcCallCatalog implements CallCatalog {

    private static final String SELECT = """
            SELECT id, name, status, start_at, end_at, instructions,
                   (start_at <= now() AND now() < end_at) AS window_open
            FROM calls
            """;

    private static final RowMapper<CallInfo> MAPPER = (rs, rowNum) -> new CallInfo(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            CallStatus.parse(rs.getString("status")).orElseThrow(),
            rs.getObject("start_at", OffsetDateTime.class).toInstant(),
            rs.getObject("end_at", OffsetDateTime.class).toInstant(),
            rs.getString("instructions"),
            rs.getBoolean("window_open"));

    private final JdbcTemplate jdbc;

    public JdbcCallCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CallInfo> findById(UUID callId) {
        List<CallInfo> found = jdbc.query(SELECT + " WHERE id = ?", MAPPER, callId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<CallInfo> findOpen() {
        return jdbc.query(SELECT + " WHERE status = 'PUBLISHED' AND start_at <= now() AND now() < end_at ORDER BY end_at, id",
                MAPPER);
    }

    @Override
    public Optional<UUID> activeExceptionId(UUID callId, UUID guardianId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id
                FROM call_exceptions
                WHERE call_id = ? AND guardian_id = ? AND granted_at <= now() AND expires_at > now()
                ORDER BY expires_at DESC
                LIMIT 1
                """, UUID.class, callId, guardianId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }
}
