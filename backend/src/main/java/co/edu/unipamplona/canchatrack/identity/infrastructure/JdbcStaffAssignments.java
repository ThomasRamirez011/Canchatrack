package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.identity.application.StaffAssignments;

/**
 * Lee {@code category_assignments} y {@code evaluator_assignments} (tablas del módulo players) con SQL de solo
 * lectura, como lo hace {@code documents} con el entrenador titular: evita que identity dependa de las clases de
 * players (ciclo). Un cambio en esas tablas obliga a revisar estas dos consultas.
 */
@Repository
public class JdbcStaffAssignments implements StaffAssignments {

    private static final String HEAD_COACH_SQL = """
            SELECT EXISTS (SELECT 1 FROM category_assignments
                           WHERE player_id = ? AND head_coach_id = ?
                             AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now()))
            """;

    private static final String EVALUATOR_SQL = """
            SELECT EXISTS (SELECT 1 FROM evaluator_assignments
                           WHERE player_id = ? AND evaluator_id = ?
                             AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now()))
            """;

    private final JdbcTemplate jdbc;

    public JdbcStaffAssignments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isHeadCoachOf(UUID userId, UUID playerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(HEAD_COACH_SQL, Boolean.class, playerId, userId));
    }

    @Override
    public boolean isEvaluatorOf(UUID userId, UUID playerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(EVALUATOR_SQL, Boolean.class, playerId, userId));
    }
}
