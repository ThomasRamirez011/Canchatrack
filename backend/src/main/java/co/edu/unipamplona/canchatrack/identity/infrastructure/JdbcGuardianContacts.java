package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;

/** Vigencia del vínculo evaluada con la hora de la base de datos, igual que {@link JdbcGuardianLinks}. */
@Repository
public class JdbcGuardianContacts implements GuardianContacts {

    private final JdbcTemplate jdbc;

    public JdbcGuardianContacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> guardianUserIdsOf(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(playerIds.size(), "?"));
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT g.user_id
                FROM player_guardians pg
                         JOIN guardians g ON g.id = pg.guardian_id
                         JOIN users u ON u.id = g.user_id
                WHERE pg.player_id IN (%s)
                  AND pg.valid_from <= now() AND (pg.valid_to IS NULL OR pg.valid_to > now())
                  AND u.status = 'ACTIVE'
                """.formatted(placeholders), UUID.class, playerIds.toArray()));
    }
}
