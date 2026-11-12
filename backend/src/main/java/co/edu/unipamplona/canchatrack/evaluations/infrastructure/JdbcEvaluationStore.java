package co.edu.unipamplona.canchatrack.evaluations.infrastructure;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.evaluations.application.DecisionView;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationStore;
import co.edu.unipamplona.canchatrack.evaluations.application.RoundView;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

@Repository
public class JdbcEvaluationStore implements EvaluationStore {

    /** SQLSTATE que levanta el disparador de la base cuando la ronda ya está cerrada (RN-08..RN-10). */
    private static final String ROUND_CLOSED_STATE = "CT003";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> CRITERIA_TYPE = new TypeReference<>() {
    };

    private static final String ROUND_SELECT = """
            SELECT id, player_id, period_start, period_end, due_at, status, result, follow_up_plan, closed_at
            FROM evaluation_rounds
            """;

    private static final RowMapper<RoundView> ROUND = (rs, rowNum) -> new RoundView(rs.getObject("id", UUID.class),
            rs.getObject("player_id", UUID.class), rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class), instant(rs.getObject("due_at", OffsetDateTime.class)),
            rs.getString("status"), rs.getString("result"), rs.getString("follow_up_plan"),
            instant(rs.getObject("closed_at", OffsetDateTime.class)), null, null);

    private static final String DECISION_SELECT = """
            SELECT d.id, d.round_id, d.evaluator_id, d.verdict, d.justification, d.actions, d.criteria::text AS criteria,
                   d.period_start, d.period_end,
                   (d.period_start = r.period_start AND d.period_end = r.period_end) AS is_current,
                   d.decided_at, d.updated_at
            FROM evaluator_decisions d
                     JOIN evaluation_rounds r ON r.id = d.round_id
            """;

    private static final RowMapper<DecisionView> DECISION = (rs, rowNum) -> new DecisionView(rs.getObject("id", UUID.class),
            rs.getObject("round_id", UUID.class), rs.getObject("evaluator_id", UUID.class), rs.getString("verdict"),
            rs.getString("justification"), rs.getString("actions"), criteria(rs.getString("criteria")),
            rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
            rs.getBoolean("is_current"), instant(rs.getObject("decided_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcEvaluationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Map<String, Object> criteria(String json) {
        try {
            return json == null ? Map.of() : MAPPER.readValue(json, CRITERIA_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Criterios almacenados con formato inválido", e);
        }
    }

    private static String criteriaJson(Map<String, Object> criteria) {
        try {
            return MAPPER.writeValueAsString(criteria);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    @Override
    public Optional<UUID> insertRound(UUID playerId, LocalDate periodStart, LocalDate periodEnd, Instant dueAt, UUID openedBy) {
        return jdbc.queryForList("""
                INSERT INTO evaluation_rounds (player_id, period_start, period_end, due_at, opened_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id
                """, UUID.class, playerId, periodStart, periodEnd, utc(dueAt), openedBy).stream().findFirst();
    }

    @Override
    public Optional<RoundView> findRound(UUID roundId, boolean lock) {
        return jdbc.query(ROUND_SELECT + "WHERE id = ?" + (lock ? " FOR UPDATE" : ""), ROUND, roundId).stream().findFirst();
    }

    @Override
    public List<RoundView> findRounds(UUID playerId) {
        return jdbc.query(ROUND_SELECT + "WHERE player_id = ? ORDER BY period_start DESC, created_at DESC", ROUND, playerId);
    }

    @Override
    public List<DecisionView> findDecisions(UUID roundId) {
        return jdbc.query(DECISION_SELECT + "WHERE d.round_id = ? ORDER BY d.decided_at, d.evaluator_id", DECISION, roundId);
    }

    @Override
    public DecisionView upsertDecision(UUID roundId, UUID evaluatorId, String verdict, String justification, String actions,
                                       Map<String, Object> criteria, LocalDate periodStart, LocalDate periodEnd) {
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, justification, actions, criteria,
                                                     period_start, period_end)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (round_id, evaluator_id) DO UPDATE
                        SET verdict = EXCLUDED.verdict, justification = EXCLUDED.justification,
                            actions = EXCLUDED.actions, criteria = EXCLUDED.criteria,
                            period_start = EXCLUDED.period_start, period_end = EXCLUDED.period_end, decided_at = now()
                    RETURNING id
                    """, UUID.class, roundId, evaluatorId, verdict, justification, actions, criteriaJson(criteria),
                    periodStart, periodEnd);
        } catch (DataAccessException e) {
            if (isRoundClosed(e)) {
                throw new DomainException(ErrorCode.ROUND_CLOSED, null);
            }
            throw e;
        }
        return jdbc.queryForObject(DECISION_SELECT + "WHERE d.id = ?", DECISION, id);
    }

    private static boolean isRoundClosed(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && ROUND_CLOSED_STATE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void updatePeriod(UUID roundId, LocalDate periodStart, LocalDate periodEnd, Instant dueAt) {
        jdbc.update("""
                UPDATE evaluation_rounds
                SET period_start = ?, period_end = ?, due_at = COALESCE(CAST(? AS timestamptz), due_at),
                    optimistic_version = optimistic_version + 1
                WHERE id = ? AND status = 'OPEN'
                """, periodStart, periodEnd, utc(dueAt), roundId);
    }

    @Override
    public boolean close(UUID roundId, String result, String followUpPlan) {
        return jdbc.update("""
                UPDATE evaluation_rounds
                SET status = 'CLOSED', result = ?, follow_up_plan = ?, closed_at = now(),
                    optimistic_version = optimistic_version + 1
                WHERE id = ? AND status = 'OPEN'
                """, result, followUpPlan, roundId) == 1;
    }

    @Override
    public Optional<UUID> findApprovedRoundId(UUID playerId) {
        return jdbc.queryForList("""
                SELECT id FROM evaluation_rounds
                WHERE player_id = ? AND status = 'CLOSED' AND result = 'APPROVED'
                ORDER BY closed_at DESC LIMIT 1
                """, UUID.class, playerId).stream().findFirst();
    }
}
