package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.players.application.CategoryInfo;
import co.edu.unipamplona.canchatrack.players.application.CategoryStore;
import co.edu.unipamplona.canchatrack.players.application.DuplicateCategoryNameException;
import co.edu.unipamplona.canchatrack.players.application.OverlappingCategoryRangeException;

@Repository
public class JdbcCategoryStore implements CategoryStore {

    /** SQLSTATE de la restricción de exclusión (rangos de nacimiento solapados, V14). */
    private static final String EXCLUSION_VIOLATION = "23P01";

    /** El cupo ocupado excluye a quien ya no ocupa plaza: retirados, rechazados y temporada cerrada. */
    private static final String SELECT = """
            SELECT c.id, c.name, c.birth_year_min, c.birth_year_max, c.capacity, c.next_category_id, c.active,
                   (SELECT count(*)
                      FROM category_assignments ca
                      JOIN players p ON p.id = ca.player_id
                     WHERE ca.category_id = c.id AND ca.valid_to IS NULL
                       AND p.status NOT IN ('WITHDRAWN', 'SEASON_CLOSED', 'REJECTED'))::int AS occupied
            FROM categories c
            """;

    private static final RowMapper<CategoryInfo> MAPPER = (rs, rowNum) -> new CategoryInfo(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getInt("birth_year_min"),
            rs.getInt("birth_year_max"),
            rs.getInt("capacity"),
            rs.getInt("occupied"),
            rs.getObject("next_category_id", UUID.class),
            rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public JdbcCategoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CategoryInfo insert(String name, int birthYearMin, int birthYearMax, int capacity, UUID nextCategoryId,
                               UUID createdBy) {
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO categories (name, birth_year_min, birth_year_max, capacity, next_category_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, UUID.class, name, birthYearMin, birthYearMax, capacity, nextCategoryId, createdBy);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        return findById(id).orElseThrow();
    }

    @Override
    public List<CategoryInfo> findAll(boolean activeOnly) {
        return jdbc.query(SELECT + (activeOnly ? " WHERE c.active" : "") + " ORDER BY c.birth_year_min DESC, c.id", MAPPER);
    }

    @Override
    public Optional<CategoryInfo> findById(UUID categoryId) {
        return first(jdbc.query(SELECT + " WHERE c.id = ?", MAPPER, categoryId));
    }

    @Override
    public Optional<CategoryInfo> findByIdForUpdate(UUID categoryId) {
        // FOR UPDATE no admite subconsultas agregadas en el mismo SELECT: se bloquea la fila y luego se lee completa.
        jdbc.query("SELECT 1 FROM categories WHERE id = ? FOR UPDATE", rs -> { }, categoryId);
        return findById(categoryId);
    }

    @Override
    public Optional<CategoryInfo> findActiveCovering(int birthYear) {
        return first(jdbc.query(SELECT + " WHERE c.active AND c.birth_year_min <= ? AND ? <= c.birth_year_max ORDER BY c.id",
                MAPPER, birthYear, birthYear));
    }

    @Override
    public Optional<CategoryInfo> findActiveOverlapping(int birthYearMin, int birthYearMax, UUID excludeCategoryId) {
        return first(jdbc.query(SELECT + """
                 WHERE c.active AND c.birth_year_min <= ? AND ? <= c.birth_year_max
                   AND (?::uuid IS NULL OR c.id <> ?::uuid)
                 ORDER BY c.birth_year_min, c.id
                """, MAPPER, birthYearMax, birthYearMin, excludeCategoryId, excludeCategoryId));
    }

    @Override
    public void update(UUID categoryId, String name, int birthYearMin, int birthYearMax, int capacity,
                       UUID nextCategoryId, boolean active) {
        try {
            jdbc.update("""
                    UPDATE categories
                       SET name = ?, birth_year_min = ?, birth_year_max = ?, capacity = ?, next_category_id = ?, active = ?
                     WHERE id = ?
                    """, name, birthYearMin, birthYearMax, capacity, nextCategoryId, active, categoryId);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return new DuplicateCategoryNameException();
        }
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
            return new OverlappingCategoryRangeException();
        }
        return e;
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
