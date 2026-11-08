package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.players.application.DuplicateSeasonNameException;
import co.edu.unipamplona.canchatrack.players.application.SeasonInfo;
import co.edu.unipamplona.canchatrack.players.application.SeasonStore;
import co.edu.unipamplona.canchatrack.players.domain.SeasonStatus;

@Repository
public class JdbcSeasonStore implements SeasonStore {

    private static final String SELECT = "SELECT id, name, start_at, end_at, status, created_at FROM seasons";

    private static final RowMapper<SeasonInfo> MAPPER = (rs, rowNum) -> new SeasonInfo(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getObject("start_at", OffsetDateTime.class).toInstant(),
            rs.getObject("end_at", OffsetDateTime.class).toInstant(),
            SeasonStatus.parse(rs.getString("status")).orElseThrow(),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public JdbcSeasonStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SeasonInfo insert(String name, Instant startAt, Instant endAt, UUID createdBy) {
        UUID id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO seasons (name, start_at, end_at, created_by) VALUES (?, ?, ?, ?) RETURNING id",
                    UUID.class, name, utc(startAt), utc(endAt), createdBy);
        } catch (DuplicateKeyException e) {
            throw new DuplicateSeasonNameException();
        }
        return findById(id).orElseThrow();
    }

    @Override
    public List<SeasonInfo> findAll() {
        return jdbc.query(SELECT + " ORDER BY start_at DESC, id", MAPPER);
    }

    @Override
    public Optional<SeasonInfo> findById(UUID seasonId) {
        return first(jdbc.query(SELECT + " WHERE id = ?", MAPPER, seasonId));
    }

    @Override
    public Optional<SeasonInfo> findByIdForUpdate(UUID seasonId) {
        return first(jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", MAPPER, seasonId));
    }

    @Override
    public boolean activate(UUID seasonId) {
        return jdbc.update("UPDATE seasons SET status = 'ACTIVE' WHERE id = ? AND status = 'PLANNED'", seasonId) == 1;
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
