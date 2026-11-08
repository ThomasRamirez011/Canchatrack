package co.edu.unipamplona.canchatrack.players.infrastructure;

import static co.edu.unipamplona.canchatrack.players.infrastructure.JdbcSeasonStore.utc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.players.application.CallDetail;
import co.edu.unipamplona.canchatrack.players.application.CallExceptionInfo;
import co.edu.unipamplona.canchatrack.players.application.CallStore;
import co.edu.unipamplona.canchatrack.players.domain.CallStatus;

/** Toda vigencia ("ventana abierta", "habilitación vigente") se evalúa con {@code now()} de la base de datos (RN-01). */
@Repository
public class JdbcCallStore implements CallStore {

    private static final int MAX_ROWS = 200;

    private static final String SELECT_CALL = """
            SELECT id, season_id, name, start_at, end_at, instructions, status,
                   (start_at <= now() AND now() < end_at) AS window_open, created_at, updated_at
            FROM calls
            """;

    private static final String SELECT_EXCEPTION = """
            SELECT id, call_id, guardian_id, reason, granted_by, granted_at, expires_at,
                   (granted_at <= now() AND expires_at > now()) AS active
            FROM call_exceptions
            """;

    private static final RowMapper<CallDetail> CALL_MAPPER = (rs, rowNum) -> new CallDetail(
            rs.getObject("id", UUID.class),
            rs.getObject("season_id", UUID.class),
            rs.getString("name"),
            rs.getObject("start_at", OffsetDateTime.class).toInstant(),
            rs.getObject("end_at", OffsetDateTime.class).toInstant(),
            rs.getString("instructions"),
            CallStatus.parse(rs.getString("status")).orElseThrow(),
            rs.getBoolean("window_open"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private static final RowMapper<CallExceptionInfo> EXCEPTION_MAPPER = (rs, rowNum) -> new CallExceptionInfo(
            rs.getObject("id", UUID.class),
            rs.getObject("call_id", UUID.class),
            rs.getObject("guardian_id", UUID.class),
            rs.getString("reason"),
            rs.getObject("granted_by", UUID.class),
            rs.getObject("granted_at", OffsetDateTime.class).toInstant(),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public JdbcCallStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    @Override
    public CallDetail insert(UUID seasonId, String name, Instant startAt, Instant endAt, String instructions,
                             UUID createdBy) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO calls (season_id, name, start_at, end_at, instructions, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, seasonId, name, utc(startAt), utc(endAt), instructions, createdBy);
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<CallDetail> findById(UUID callId) {
        return first(jdbc.query(SELECT_CALL + " WHERE id = ?", CALL_MAPPER, callId));
    }

    @Override
    public Optional<CallDetail> findByIdForUpdate(UUID callId) {
        return first(jdbc.query(SELECT_CALL + " WHERE id = ? FOR UPDATE", CALL_MAPPER, callId));
    }

    @Override
    public List<CallDetail> find(UUID seasonId, CallStatus status) {
        StringBuilder sql = new StringBuilder(SELECT_CALL).append(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (seasonId != null) {
            sql.append(" AND season_id = ?");
            args.add(seasonId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY start_at DESC, id LIMIT ").append(MAX_ROWS);
        return jdbc.query(sql.toString(), CALL_MAPPER, args.toArray());
    }

    @Override
    public void update(UUID callId, String name, Instant startAt, Instant endAt, String instructions) {
        jdbc.update("UPDATE calls SET name = ?, start_at = ?, end_at = ?, instructions = ? WHERE id = ?",
                name, utc(startAt), utc(endAt), instructions, callId);
    }

    @Override
    public boolean publish(UUID callId) {
        return jdbc.update("UPDATE calls SET status = 'PUBLISHED' WHERE id = ? AND status = 'DRAFT' AND end_at > now()",
                callId) == 1;
    }

    @Override
    public boolean cancel(UUID callId) {
        return jdbc.update("UPDATE calls SET status = 'CANCELLED' WHERE id = ? AND status IN ('DRAFT', 'PUBLISHED')",
                callId) == 1;
    }

    @Override
    public CallExceptionInfo insertException(UUID callId, UUID guardianId, String reason, UUID grantedBy,
                                             Instant expiresAt) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO call_exceptions (call_id, guardian_id, reason, granted_by, expires_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, callId, guardianId, reason, grantedBy, utc(expiresAt));
        return jdbc.queryForObject(SELECT_EXCEPTION + " WHERE id = ?", EXCEPTION_MAPPER, id);
    }

    @Override
    public List<CallExceptionInfo> findExceptions(UUID callId) {
        return jdbc.query(SELECT_EXCEPTION + " WHERE call_id = ? ORDER BY granted_at DESC, id", EXCEPTION_MAPPER,
                callId);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
