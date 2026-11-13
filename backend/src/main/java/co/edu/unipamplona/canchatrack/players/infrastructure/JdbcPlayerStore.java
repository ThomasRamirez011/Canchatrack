package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;
import co.edu.unipamplona.canchatrack.players.application.DuplicatePlayerDocumentException;
import co.edu.unipamplona.canchatrack.players.application.PageResult;
import co.edu.unipamplona.canchatrack.players.application.PlayerDetail;
import co.edu.unipamplona.canchatrack.players.application.PlayerFilter;
import co.edu.unipamplona.canchatrack.players.application.PlayerRecord;
import co.edu.unipamplona.canchatrack.players.application.PlayerStore;
import co.edu.unipamplona.canchatrack.players.application.PlayerSummary;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

@Repository
public class JdbcPlayerStore implements PlayerStore {

    /** El vínculo acudiente-jugador se evalúa con el reloj de la base de datos, igual que en identity. */
    private static final String LINKED_TO_USER = """
            EXISTS (SELECT 1
                    FROM player_guardians pg
                             JOIN guardians g ON g.id = pg.guardian_id
                    WHERE pg.player_id = p.id
                      AND g.user_id = ?
                      AND pg.valid_from <= now()
                      AND (pg.valid_to IS NULL OR pg.valid_to > now()))
            """;

    /** Jugadores que el usuario entrena (entrenador titular vigente) o evalúa (asignación vigente), con el reloj de la base. */
    private static final String ASSIGNED_TO_USER = """
            (EXISTS (SELECT 1 FROM category_assignments ca
                     WHERE ca.player_id = p.id
                       AND ca.head_coach_id = ?
                       AND ca.valid_from <= now()
                       AND (ca.valid_to IS NULL OR ca.valid_to > now()))
             OR EXISTS (SELECT 1 FROM evaluator_assignments ea
                        WHERE ea.player_id = p.id
                          AND ea.evaluator_id = ?
                          AND ea.valid_from <= now()
                          AND (ea.valid_to IS NULL OR ea.valid_to > now())))
            """;

    private final JdbcTemplate jdbc;

    public JdbcPlayerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlayerRecord> findByDocumentForUpdate(IdDocumentType type, String normalizedNumber) {
        List<PlayerRecord> found = jdbc.query("""
                SELECT id, code, status FROM players WHERE document_type = ? AND document_number = ? FOR UPDATE
                """, (rs, rowNum) -> new PlayerRecord(
                rs.getObject("id", UUID.class), rs.getString("code"),
                PlayerStatus.parse(rs.getString("status")).orElseThrow()), type.name(), normalizedNumber);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public PlayerRecord insert(String firstName, String lastName, IdDocumentType type, String normalizedNumber,
                               LocalDate birthDate, UUID createdBy) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, created_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id, code, status
                    """, (rs, rowNum) -> new PlayerRecord(
                    rs.getObject("id", UUID.class), rs.getString("code"),
                    PlayerStatus.parse(rs.getString("status")).orElseThrow()),
                    firstName, lastName, type.name(), normalizedNumber, birthDate, createdBy);
        } catch (DuplicateKeyException e) {
            throw new DuplicatePlayerDocumentException();
        }
    }

    @Override
    public void markRefiled(UUID playerId) {
        jdbc.update("UPDATE players SET status = 'SUBMITTED', optimistic_version = optimistic_version + 1 WHERE id = ?",
                playerId);
    }

    @Override
    public boolean isLinkedGuardian(UUID playerId, UUID guardianId) {
        Boolean linked = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1
                               FROM player_guardians
                               WHERE player_id = ? AND guardian_id = ?
                                 AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now()))
                """, Boolean.class, playerId, guardianId);
        return Boolean.TRUE.equals(linked);
    }

    @Override
    public void linkGuardian(UUID playerId, UUID guardianId, String relationship, boolean primary, UUID createdBy) {
        jdbc.update("""
                INSERT INTO player_guardians (player_id, guardian_id, relationship, is_primary, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, playerId, guardianId, relationship, primary, createdBy);
    }

    @Override
    public Optional<PlayerDetail> findDetail(UUID playerId) {
        List<PlayerDetail> found = jdbc.query("""
                SELECT id, code, first_name, last_name, document_type, document_number, birth_date, status, created_at
                FROM players WHERE id = ?
                """, (rs, rowNum) -> new PlayerDetail(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("document_type"), rs.getString("document_number"),
                rs.getObject("birth_date", LocalDate.class), PlayerStatus.parse(rs.getString("status")).orElseThrow(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), playerId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public PageResult<PlayerSummary> list(PlayerFilter filter, int page, int size) {
        if (filter.matchesNothing()) {
            return new PageResult<>(List.of(), page, size, 0);
        }
        PlayerScope scope = filter.scope();
        UUID userId = filter.userId();
        PlayerStatus status = filter.status();
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (scope == PlayerScope.LINKED_GUARDIAN) {
            where.append(" AND ").append(LINKED_TO_USER);
            args.add(userId);
        }
        if (scope == PlayerScope.ASSIGNED_STAFF) {
            where.append(" AND ").append(ASSIGNED_TO_USER);
            args.add(userId);
            args.add(userId);
        }
        if (status != null) {
            where.append(" AND p.status = ?");
            args.add(status.name());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM players p" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<PlayerSummary> items = jdbc.query("""
                SELECT p.id, p.code, p.first_name, p.last_name, p.birth_date, p.status
                FROM players p
                """ + where + " ORDER BY p.created_at DESC, p.id LIMIT ? OFFSET ?",
                (rs, rowNum) -> new PlayerSummary(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getObject("birth_date", LocalDate.class),
                        PlayerStatus.parse(rs.getString("status")).orElseThrow()),
                pageArgs.toArray());
        return new PageResult<>(items, page, size, total == null ? 0 : total);
    }
}
