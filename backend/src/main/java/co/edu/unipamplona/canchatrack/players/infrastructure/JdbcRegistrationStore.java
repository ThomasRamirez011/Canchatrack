package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.players.application.RegistrationStore;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

@Repository
public class JdbcRegistrationStore implements RegistrationStore {

    private final JdbcTemplate jdbc;

    public JdbcRegistrationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlayerStatus> lockStatus(UUID playerId) {
        return jdbc.queryForList("SELECT status FROM players WHERE id = ? FOR UPDATE", String.class, playerId).stream()
                .findFirst().flatMap(PlayerStatus::parse);
    }

    @Override
    public void transition(UUID playerId, PlayerStatus to) {
        jdbc.update("UPDATE players SET status = ?, optimistic_version = optimistic_version + 1 WHERE id = ?",
                to.name(), playerId);
    }

    @Override
    public List<UUID> activePlayerIds() {
        return jdbc.queryForList("SELECT id FROM players WHERE status = 'ACTIVE' ORDER BY id", UUID.class);
    }
}
