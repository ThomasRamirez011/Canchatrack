package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.players.application.DuplicateEnrollmentException;
import co.edu.unipamplona.canchatrack.players.application.EnrollmentRecord;
import co.edu.unipamplona.canchatrack.players.application.EnrollmentStore;
import co.edu.unipamplona.canchatrack.players.application.EnrollmentSummary;

@Repository
public class JdbcEnrollmentStore implements EnrollmentStore {

    private final JdbcTemplate jdbc;

    public JdbcEnrollmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EnrollmentRecord insert(UUID playerId, UUID callId, UUID guardianId, UUID exceptionId, UUID createdBy) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO enrollments (player_id, call_id, guardian_id, call_exception_id, created_by)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id, filing_code, submitted_at
                    """, (rs, rowNum) -> new EnrollmentRecord(
                    rs.getObject("id", UUID.class), rs.getString("filing_code"),
                    rs.getObject("submitted_at", OffsetDateTime.class).toInstant()),
                    playerId, callId, guardianId, exceptionId, createdBy);
        } catch (DuplicateKeyException e) {
            throw new DuplicateEnrollmentException();
        }
    }

    @Override
    public List<EnrollmentSummary> findByPlayer(UUID playerId) {
        return jdbc.query("""
                SELECT e.id, e.filing_code, e.call_id, c.name AS call_name, e.submitted_at
                FROM enrollments e
                         JOIN calls c ON c.id = e.call_id
                WHERE e.player_id = ?
                ORDER BY e.submitted_at DESC, e.id
                """, (rs, rowNum) -> new EnrollmentSummary(
                rs.getObject("id", UUID.class), rs.getString("filing_code"), rs.getObject("call_id", UUID.class),
                rs.getString("call_name"), rs.getObject("submitted_at", OffsetDateTime.class).toInstant()), playerId);
    }
}
