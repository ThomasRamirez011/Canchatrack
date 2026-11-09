package co.edu.unipamplona.canchatrack.attendance.infrastructure;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.attendance.application.AttendanceStore;
import co.edu.unipamplona.canchatrack.attendance.application.PlayerAttendanceSummary;
import co.edu.unipamplona.canchatrack.attendance.application.RecordInfo;
import co.edu.unipamplona.canchatrack.attendance.application.SyncBatch;
import co.edu.unipamplona.canchatrack.attendance.domain.AttendanceRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

@Repository
public class JdbcAttendanceStore implements AttendanceStore {

    /** Sesión cerrada (trigger de V8): una marca se coló entre la comprobación y la escritura. */
    private static final String SESSION_CLOSED = "CT002";

    private static final RowMapper<RecordInfo> RECORD = (rs, rowNum) -> new RecordInfo(rs.getObject("id", UUID.class),
            rs.getObject("player_id", UUID.class), rs.getString("status"), rs.getBoolean("excuse_accepted"));

    private final JdbcTemplate jdbc;

    public JdbcAttendanceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    @Override
    public boolean lockSession(UUID eventId, boolean exclusive) {
        jdbc.update("INSERT INTO attendance_sessions (event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId);
        return jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM attendance_sessions WHERE event_id = ?"
                + (exclusive ? " FOR UPDATE" : " FOR SHARE"), Boolean.class, eventId);
    }

    @Override
    public boolean isClosed(UUID eventId) {
        return closedAt(eventId).isPresent();
    }

    @Override
    public Optional<Instant> closedAt(UUID eventId) {
        return jdbc.query("SELECT closed_at FROM attendance_sessions WHERE event_id = ? AND closed_at IS NOT NULL",
                (rs, rowNum) -> rs.getObject("closed_at", OffsetDateTime.class).toInstant(), eventId).stream().findFirst();
    }

    @Override
    public Optional<SyncBatch> findBatch(UUID userId, String key) {
        return jdbc.query("""
                SELECT id, event_id, record_count, accepted_count, duplicate_count, rejected_count
                FROM sync_batches WHERE received_by = ? AND idempotency_key = ?
                """, (rs, rowNum) -> new SyncBatch(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getInt("record_count"), rs.getInt("accepted_count"), rs.getInt("duplicate_count"), rs.getInt("rejected_count")),
                userId, key).stream().findFirst();
    }

    @Override
    public Optional<UUID> insertBatch(UUID eventId, UUID userId, String key) {
        return jdbc.queryForList("""
                INSERT INTO sync_batches (event_id, idempotency_key, received_by) VALUES (?, ?, ?)
                ON CONFLICT (received_by, idempotency_key) DO NOTHING RETURNING id
                """, UUID.class, eventId, key, userId).stream().findFirst();
    }

    @Override
    public void finishBatch(UUID batchId, int received, int accepted, int duplicates, int rejected) {
        jdbc.update("UPDATE sync_batches SET record_count = ?, accepted_count = ?, duplicate_count = ?, rejected_count = ? WHERE id = ?",
                received, accepted, duplicates, rejected, batchId);
    }

    @Override
    public boolean insertRecord(UUID eventId, UUID playerId, String status, boolean excuseAccepted, UUID clientUuid,
                                Instant clientRecordedAt, UUID recordedBy, UUID batchId) {
        try {
            return jdbc.update("""
                    INSERT INTO attendance_records (event_id, player_id, status, excuse_accepted, client_uuid, client_recorded_at,
                                                    recorded_by, sync_batch_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, eventId, playerId, status, excuseAccepted, clientUuid,
                    clientRecordedAt == null ? null : OffsetDateTime.ofInstant(clientRecordedAt, ZoneOffset.UTC), recordedBy, batchId) == 1;
        } catch (DataAccessException e) {
            throw translateClosed(e);
        }
    }

    @Override
    public List<RecordInfo> recordsOf(UUID eventId) {
        return jdbc.query("SELECT id, player_id, status, excuse_accepted FROM attendance_records WHERE event_id = ?", RECORD, eventId);
    }

    @Override
    public Optional<RecordInfo> findRecord(UUID eventId, UUID playerId) {
        return jdbc.query("SELECT id, player_id, status, excuse_accepted FROM attendance_records WHERE event_id = ? AND player_id = ?",
                RECORD, eventId, playerId).stream().findFirst();
    }

    @Override
    public int markAbsent(UUID eventId, Collection<UUID> playerIds, UUID recordedBy) {
        int created = 0;
        for (UUID playerId : playerIds) {
            created += jdbc.update("""
                    INSERT INTO attendance_records (event_id, player_id, status, client_uuid, recorded_by)
                    VALUES (?, ?, 'ABSENT', gen_random_uuid(), ?)
                    ON CONFLICT (event_id, player_id) DO NOTHING
                    """, eventId, playerId, recordedBy);
        }
        return created;
    }

    @Override
    public Optional<Instant> closeSession(UUID eventId, UUID closedBy) {
        return jdbc.query("UPDATE attendance_sessions SET closed_at = now(), closed_by = ? WHERE event_id = ? AND closed_at IS NULL RETURNING closed_at",
                (rs, rowNum) -> rs.getObject("closed_at", OffsetDateTime.class).toInstant(), closedBy, eventId).stream().findFirst();
    }

    @Override
    public void correct(UUID recordId, String previousStatus, String newStatus, boolean excuseAccepted, String reason, UUID correctedBy) {
        // Alcance de transacción (true): autoriza el cambio sobre una sesión cerrada solo dentro de esta corrección.
        jdbc.query("SELECT set_config('canchatrack.attendance_correction', 'on', true)", rs -> { });
        jdbc.update("INSERT INTO attendance_corrections (record_id, previous_status, new_status, reason, corrected_by) VALUES (?, ?, ?, ?, ?)",
                recordId, previousStatus, newStatus, reason, correctedBy);
        jdbc.update("UPDATE attendance_records SET status = ?, excuse_accepted = ? WHERE id = ?", newStatus, excuseAccepted, recordId);
    }

    @Override
    public Optional<PlayerAttendanceSummary> summaryOf(UUID playerId) {
        return jdbc.query("""
                SELECT p.id,
                       count(r.id)                                                            AS sessions,
                       count(r.id) FILTER (WHERE r.status = 'PRESENT')                        AS present,
                       count(r.id) FILTER (WHERE r.status = 'LATE')                           AS late,
                       count(r.id) FILTER (WHERE r.status = 'EXCUSED')                        AS excused,
                       count(r.id) FILTER (WHERE r.status = 'EXCUSED' AND r.excuse_accepted)  AS accepted,
                       count(r.id) FILTER (WHERE r.status = 'ABSENT')                         AS absent
                FROM players p
                         LEFT JOIN (attendance_records r JOIN attendance_sessions s ON s.event_id = r.event_id AND s.closed_at IS NOT NULL)
                                   ON r.player_id = p.id
                WHERE p.id = ?
                GROUP BY p.id
                """, (rs, rowNum) -> {
            int sessions = rs.getInt("sessions");
            int present = rs.getInt("present");
            int late = rs.getInt("late");
            int accepted = rs.getInt("accepted");
            return new PlayerAttendanceSummary(rs.getObject("id", UUID.class), sessions, present, late, rs.getInt("excused"),
                    accepted, rs.getInt("absent"), AttendanceRules.percentage(present + late, sessions, accepted));
        }, playerId).stream().findFirst();
    }

    private static RuntimeException translateClosed(DataAccessException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sql && SESSION_CLOSED.equals(sql.getSQLState())) {
                return new DomainException(ErrorCode.ATTENDANCE_SESSION_CLOSED, null);
            }
            cause = cause.getCause();
        }
        return e;
    }
}
