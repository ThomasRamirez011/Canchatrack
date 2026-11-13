package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.reports.application.ExportStore;
import co.edu.unipamplona.canchatrack.reports.application.ReportSettings;
import co.edu.unipamplona.canchatrack.reports.domain.ExportRules;

/**
 * Modelo de lectura de las exportaciones del ERS 7.3: SQL de solo lectura sobre tablas de otros módulos, sin depender de
 * sus clases. Todo valor externo viaja como parámetro; el SQL se arma solo con fragmentos fijos. Las fechas y horas se
 * expresan en la zona del club y las celdas salen en el orden de {@code ExportColumns}.
 */
@Repository
public class JdbcExportStore implements ExportStore {

    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public JdbcExportStore(JdbcTemplate jdbc, ReportSettings settings) {
        this.jdbc = jdbc;
        this.zone = settings.zone();
    }

    @Override
    public Optional<Period> seasonPeriod(UUID seasonId) {
        return jdbc.query("SELECT start_at, end_at FROM seasons WHERE id = ?",
                (rs, rowNum) -> new Period(instant(rs, "start_at"), instant(rs, "end_at")), seasonId).stream().findFirst();
    }

    @Override
    public boolean categoryExists(UUID categoryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM categories WHERE id = ?)", Boolean.class, categoryId));
    }

    @Override
    public boolean tournamentExists(UUID tournamentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM tournaments WHERE id = ?)", Boolean.class, tournamentId));
    }

    @Override
    public List<List<Object>> roster(UUID categoryId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.name AS category_name, p.code, p.first_name || ' ' || p.last_name AS player_name, p.birth_date,
                       p.status, coach.full_name AS coach_name, gu.full_name AS guardian_name, ca.valid_from
                FROM category_assignments ca
                         JOIN players p ON p.id = ca.player_id
                         JOIN categories c ON c.id = ca.category_id
                         JOIN users coach ON coach.id = ca.head_coach_id
                         LEFT JOIN player_guardians pg ON pg.player_id = p.id AND pg.is_primary AND pg.valid_to IS NULL
                         LEFT JOIN guardians g ON g.id = pg.guardian_id
                         LEFT JOIN users gu ON gu.id = g.user_id
                WHERE ca.valid_to IS NULL
                """);
        List<Object> args = new ArrayList<>();
        if (categoryId != null) {
            sql.append(" AND ca.category_id = ?");
            args.add(categoryId);
        }
        sql.append(" ORDER BY c.name, p.last_name, p.first_name, p.id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), cells((rs, rowNum) -> Arrays.asList(rs.getString("category_name"),
                rs.getString("code"), rs.getString("player_name"), rs.getObject("birth_date", LocalDate.class),
                rs.getString("status"), orEmpty(rs.getString("coach_name")), orEmpty(rs.getString("guardian_name")),
                day(rs, "valid_from"))), args.toArray());
    }

    @Override
    public List<List<Object>> attendance(UUID categoryId, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.start_at, e.event_type, c.name AS category_name, v.name AS venue_name, p.code,
                       p.first_name || ' ' || p.last_name AS player_name, r.status, r.excuse_accepted
                FROM attendance_records r
                         JOIN sports_events e ON e.id = r.event_id
                         JOIN attendance_sessions s ON s.event_id = e.id AND s.closed_at IS NOT NULL
                         JOIN players p ON p.id = r.player_id
                         JOIN categories c ON c.id = e.category_id
                         JOIN venues v ON v.id = e.venue_id
                WHERE e.status <> 'CANCELLED' AND e.start_at >= ? AND e.start_at < ?
                """);
        List<Object> args = new ArrayList<>(List.of(offset(from), offset(to)));
        if (categoryId != null) {
            sql.append(" AND e.category_id = ?");
            args.add(categoryId);
        }
        sql.append(" ORDER BY e.start_at, e.id, p.last_name, p.first_name, p.id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), cells((rs, rowNum) -> Arrays.asList(day(rs, "start_at"), time(rs, "start_at"),
                rs.getString("event_type"), rs.getString("category_name"), rs.getString("venue_name"),
                rs.getString("code"), rs.getString("player_name"), rs.getString("status"),
                ExportRules.yesNo(rs.getBoolean("excuse_accepted")))), args.toArray());
    }

    @Override
    public List<List<Object>> matches(UUID categoryId, UUID tournamentId, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.start_at, c.name AS category_name, e.event_type, t.name AS tournament, m.opponent,
                       v.name AS venue_name, e.status AS event_status, m.score_for, m.score_against, p.code,
                       p.first_name || ' ' || p.last_name AS player_name, ce.status AS callup_status, ce.minutes_played,
                       ce.goals
                FROM matches m
                         JOIN sports_events e ON e.id = m.event_id
                         JOIN categories c ON c.id = e.category_id
                         JOIN venues v ON v.id = e.venue_id
                         LEFT JOIN tournaments t ON t.id = m.tournament_id
                         LEFT JOIN callup_entries ce ON ce.match_id = m.id AND ce.status <> 'REMOVED'
                         LEFT JOIN players p ON p.id = ce.player_id
                WHERE e.start_at >= ? AND e.start_at < ?
                """);
        List<Object> args = new ArrayList<>(List.of(offset(from), offset(to)));
        if (categoryId != null) {
            sql.append(" AND e.category_id = ?");
            args.add(categoryId);
        }
        if (tournamentId != null) {
            sql.append(" AND m.tournament_id = ?");
            args.add(tournamentId);
        }
        sql.append(" ORDER BY e.start_at, m.id, p.last_name, p.first_name, p.id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), cells((rs, rowNum) -> {
            List<Object> row = new ArrayList<>();
            row.add(day(rs, "start_at"));
            row.add(time(rs, "start_at"));
            row.add(rs.getString("category_name"));
            row.add(rs.getString("event_type"));
            row.add(orEmpty(rs.getString("tournament")));
            row.add(rs.getString("opponent"));
            row.add(rs.getString("venue_name"));
            row.add(rs.getString("event_status"));
            row.add(nullableInt(rs, "score_for"));
            row.add(nullableInt(rs, "score_against"));
            row.add(orEmpty(rs.getString("code")));
            row.add(orEmpty(rs.getString("player_name")));
            row.add(orEmpty(rs.getString("callup_status")));
            row.add(nullableInt(rs, "minutes_played"));
            row.add(nullableInt(rs, "goals"));
            return row;
        }), args.toArray());
    }

    @Override
    public List<List<Object>> evaluations(UUID categoryId, LocalDate firstDay, LocalDate lastDay, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT coalesce(c.name, 'Sin categoría') AS category_name, p.code,
                       p.first_name || ' ' || p.last_name AS player_name, er.period_start, er.period_end, er.due_at,
                       er.status, er.result, er.closed_at,
                       count(d.id)                                                        AS emitted,
                       count(d.id) FILTER (WHERE d.verdict = 'FAVORABLE')                 AS favorable,
                       count(d.id) FILTER (WHERE d.verdict = 'FOLLOW_UP_REQUIRED')        AS follow_up,
                       count(d.id) FILTER (WHERE d.verdict = 'UNFAVORABLE')               AS unfavorable
                FROM evaluation_rounds er
                         JOIN players p ON p.id = er.player_id
                         LEFT JOIN category_assignments ca ON ca.player_id = p.id AND ca.valid_to IS NULL
                         LEFT JOIN categories c ON c.id = ca.category_id
                         LEFT JOIN evaluator_decisions d ON d.round_id = er.id
                    AND d.period_start = er.period_start AND d.period_end = er.period_end
                WHERE er.period_start <= ? AND er.period_end >= ?
                """);
        List<Object> args = new ArrayList<>(List.of(lastDay, firstDay));
        if (categoryId != null) {
            sql.append(" AND ca.category_id = ?");
            args.add(categoryId);
        }
        sql.append("""
                 GROUP BY er.id, c.name, p.id
                 ORDER BY category_name, er.period_start, p.last_name, p.first_name, er.id LIMIT ?
                """);
        args.add(limit);
        return jdbc.query(sql.toString(), cells((rs, rowNum) -> {
            List<Object> row = new ArrayList<>();
            row.add(rs.getString("category_name"));
            row.add(rs.getString("code"));
            row.add(rs.getString("player_name"));
            row.add(rs.getObject("period_start", LocalDate.class));
            row.add(rs.getObject("period_end", LocalDate.class));
            row.add(day(rs, "due_at"));
            row.add(rs.getString("status"));
            row.add(orEmpty(rs.getString("result")));
            row.add(day(rs, "closed_at"));
            row.add(rs.getInt("emitted"));
            row.add(rs.getInt("favorable"));
            row.add(rs.getInt("follow_up"));
            row.add(rs.getInt("unfavorable"));
            return row;
        }), args.toArray());
    }

    // ---------- utilidades ----------

    /** Adapta un mapeador de celdas tipadas a lo que espera {@link JdbcTemplate#query}. */
    private static RowMapper<List<Object>> cells(RowMapper<? extends List<?>> mapper) {
        return (rs, rowNum) -> new ArrayList<Object>(mapper.mapRow(rs, rowNum));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    /** Día en la zona del club, o {@code null} si la columna es nula. */
    private LocalDate day(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.atZoneSameInstant(zone).toLocalDate();
    }

    /** Hora local del club con minutos (los eventos no se programan al segundo). */
    private LocalTime time(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.atZoneSameInstant(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES);
    }
}
