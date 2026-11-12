package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.players.application.PlacementStore;
import co.edu.unipamplona.canchatrack.players.application.PlayerRecord;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

@Repository
public class JdbcPlacementStore implements PlacementStore {

    private final JdbcTemplate jdbc;

    public JdbcPlacementStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlayerRecord> lockPlayer(UUID playerId) {
        return jdbc.query("SELECT id, code, status FROM players WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new PlayerRecord(rs.getObject("id", UUID.class), rs.getString("code"),
                        PlayerStatus.valueOf(rs.getString("status"))), playerId).stream().findFirst();
    }

    @Override
    public Optional<UUID> currentCategoryId(UUID playerId) {
        return jdbc.queryForList("SELECT category_id FROM category_assignments WHERE player_id = ? AND valid_to IS NULL",
                UUID.class, playerId).stream().findFirst();
    }

    @Override
    public List<UUID> currentPanel(UUID playerId) {
        return jdbc.queryForList("SELECT evaluator_id FROM evaluator_assignments WHERE player_id = ? AND valid_to IS NULL "
                + "ORDER BY evaluator_id", UUID.class, playerId);
    }

    @Override
    public boolean transition(UUID playerId, PlayerStatus from, PlayerStatus to) {
        return jdbc.update("UPDATE players SET status = ?, optimistic_version = optimistic_version + 1 "
                + "WHERE id = ? AND status = ?", to.name(), playerId, from.name()) == 1;
    }

    @Override
    public void closeCurrentCategoryAssignment(UUID playerId) {
        jdbc.update("UPDATE category_assignments SET valid_to = now() WHERE player_id = ? AND valid_to IS NULL", playerId);
    }

    @Override
    public void insertPromotionAssignment(UUID playerId, UUID categoryId, UUID headCoachId, UUID roundId, UUID createdBy) {
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, promotion_round_id, created_by) "
                + "VALUES (?, ?, ?, 'PROMOTION', ?, ?)", playerId, categoryId, headCoachId, roundId, createdBy);
    }
}
