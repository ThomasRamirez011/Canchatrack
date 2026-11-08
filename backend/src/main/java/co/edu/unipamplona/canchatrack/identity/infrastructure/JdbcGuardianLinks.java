package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.identity.application.GuardianLinks;

@Repository
public class JdbcGuardianLinks implements GuardianLinks {

    /** Vigencia evaluada con la hora de la base de datos (ver {@link JdbcUserAccountRepository}). */
    private static final String EXISTS_SQL = """
            SELECT EXISTS (SELECT 1
                           FROM player_guardians pg
                                    JOIN guardians g ON g.id = pg.guardian_id
                           WHERE g.user_id = ?
                             AND pg.player_id = ?
                             AND pg.valid_from <= now()
                             AND (pg.valid_to IS NULL OR pg.valid_to > now()))
            """;

    private final JdbcTemplate jdbc;

    public JdbcGuardianLinks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActiveGuardianOf(UUID userId, UUID playerId) {
        Boolean linked = jdbc.queryForObject(EXISTS_SQL, Boolean.class, userId, playerId);
        return Boolean.TRUE.equals(linked);
    }
}
