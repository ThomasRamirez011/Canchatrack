package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.players.api.RosterPlayer;
import co.edu.unipamplona.canchatrack.players.application.RosterStore;

@Repository
public class JdbcRosterStore implements RosterStore {

    private final JdbcTemplate jdbc;

    public JdbcRosterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> activePlayerIds(UUID categoryId) {
        return jdbc.queryForList("""
                SELECT p.id
                FROM category_assignments ca
                         JOIN players p ON p.id = ca.player_id
                WHERE ca.category_id = ? AND ca.valid_to IS NULL AND p.status IN ('ACTIVE', 'IN_EVALUATION')
                ORDER BY p.last_name, p.first_name, p.id
                """, UUID.class, categoryId);
    }

    @Override
    public List<RosterPlayer> findPlayers(Collection<UUID> playerIds) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(playerIds.size(), "?"));
        return jdbc.query("SELECT id, code, first_name, last_name FROM players WHERE id IN (" + placeholders + ")",
                (rs, rowNum) -> new RosterPlayer(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("first_name"), rs.getString("last_name")),
                playerIds.toArray());
    }
}
