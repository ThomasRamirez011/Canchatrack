package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.players.application.AssignmentView;
import co.edu.unipamplona.canchatrack.players.application.CommitteeStore;
import co.edu.unipamplona.canchatrack.players.application.DuplicateMinuteException;
import co.edu.unipamplona.canchatrack.players.application.MinuteData;
import co.edu.unipamplona.canchatrack.players.application.PlayerRecord;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

@Repository
public class JdbcCommitteeStore implements CommitteeStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcCommitteeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlayerRecord> lockPlayer(UUID playerId) {
        List<PlayerRecord> rows = jdbc.query("SELECT id, code, status FROM players WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new PlayerRecord(rs.getObject("id", UUID.class), rs.getString("code"),
                        PlayerStatus.valueOf(rs.getString("status"))), playerId);
        return rows.stream().findFirst();
    }

    @Override
    public LocalDate today(ZoneId zone) {
        return jdbc.queryForObject("SELECT (now() AT TIME ZONE ?)::date",
                (rs, rowNum) -> rs.getObject(1, LocalDate.class), zone.getId());
    }

    @Override
    public void closeCurrentAssignment(UUID playerId) {
        jdbc.update("UPDATE category_assignments SET valid_to = now() WHERE player_id = ? AND valid_to IS NULL", playerId);
        jdbc.update("UPDATE evaluator_assignments SET valid_to = now() WHERE player_id = ? AND valid_to IS NULL", playerId);
    }

    @Override
    public UUID insertMinute(UUID playerId, MinuteData minute, String decision, String reason, UUID createdBy) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, decision_reason,
                                                   attendees, file_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    RETURNING id
                    """, UUID.class, minute.minuteNumber(), minute.meetingDate(), playerId, decision, reason,
                    attendeesJson(minute.attendees()), minute.minuteFileId(), createdBy);
        } catch (DuplicateKeyException e) {
            throw new DuplicateMinuteException();
        }
    }

    private static String attendeesJson(List<String> attendees) {
        try {
            return MAPPER.writeValueAsString(attendees);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la lista de asistentes", e);
        }
    }

    @Override
    public void insertCategoryAssignment(UUID playerId, UUID categoryId, UUID headCoachId, UUID minuteId,
                                         String capacityExceptionReason, UUID createdBy) {
        jdbc.update("""
                INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id,
                                                  capacity_exception_reason, created_by)
                VALUES (?, ?, ?, 'COMMITTEE', ?, ?, ?)
                """, playerId, categoryId, headCoachId, minuteId, capacityExceptionReason, createdBy);
    }

    @Override
    public void insertEvaluatorAssignment(UUID playerId, UUID evaluatorId, UUID minuteId, UUID createdBy) {
        jdbc.update("INSERT INTO evaluator_assignments (player_id, evaluator_id, minute_id, created_by) VALUES (?, ?, ?, ?)",
                playerId, evaluatorId, minuteId, createdBy);
    }

    @Override
    public boolean decide(UUID playerId, PlayerStatus target) {
        return jdbc.update("""
                UPDATE players SET status = ?, optimistic_version = optimistic_version + 1
                WHERE id = ? AND status IN ('SUBMITTED', 'COMMITTEE_PENDING')
                """, target.name(), playerId) == 1;
    }

    @Override
    public Optional<AssignmentView> findCurrentAssignment(UUID playerId) {
        List<AssignmentView> rows = jdbc.query("""
                SELECT p.id AS player_id, p.status, ca.category_id, c.name AS category_name, ca.head_coach_id,
                       m.minute_number, m.meeting_date, m.file_id, ca.capacity_exception_reason, ca.valid_from
                FROM category_assignments ca
                JOIN players p ON p.id = ca.player_id
                JOIN categories c ON c.id = ca.category_id
                LEFT JOIN committee_minutes m ON m.id = ca.minute_id
                WHERE ca.player_id = ? AND ca.valid_to IS NULL
                """, (rs, rowNum) -> new AssignmentView(
                rs.getObject("player_id", UUID.class),
                PlayerStatus.valueOf(rs.getString("status")),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getObject("head_coach_id", UUID.class),
                List.of(),
                rs.getString("minute_number"),
                rs.getObject("meeting_date", LocalDate.class),
                rs.getObject("file_id", UUID.class),
                rs.getString("capacity_exception_reason"),
                rs.getTimestamp("valid_from").toInstant()), playerId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        AssignmentView head = rows.get(0);
        List<UUID> evaluators = jdbc.queryForList("""
                SELECT evaluator_id FROM evaluator_assignments
                WHERE player_id = ? AND valid_to IS NULL ORDER BY evaluator_id
                """, UUID.class, playerId);
        return Optional.of(new AssignmentView(head.playerId(), head.playerStatus(), head.categoryId(),
                head.categoryName(), head.headCoachId(), evaluators, head.minuteNumber(), head.meetingDate(),
                head.minuteFileId(), head.capacityExceptionReason(), head.assignedAt()));
    }
}
