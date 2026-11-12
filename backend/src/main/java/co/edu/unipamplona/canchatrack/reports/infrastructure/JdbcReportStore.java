package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.reports.application.CategoryReceivable;
import co.edu.unipamplona.canchatrack.reports.application.ReceivableLine;
import co.edu.unipamplona.canchatrack.reports.application.ReportStore;
import co.edu.unipamplona.canchatrack.reports.application.SeasonSummary;
import co.edu.unipamplona.canchatrack.reports.domain.ReportRules;

/**
 * Modelo de lectura de los reportes: consulta con SQL de solo lectura las tablas y vistas de otros módulos
 * ({@code charge_balances}, {@code category_assignments}, {@code sports_events}, …) sin depender de sus clases. Todo valor
 * externo viaja como parámetro; el SQL se arma solo con fragmentos fijos.
 */
@Repository
public class JdbcReportStore implements ReportStore {

    private static final String RECEIVABLES_FROM = """
            FROM charge_balances cb
                     JOIN fees f ON f.id = cb.fee_id
                     JOIN players p ON p.id = cb.player_id
                     LEFT JOIN category_assignments ca ON ca.player_id = p.id AND ca.valid_to IS NULL
                     LEFT JOIN categories c ON c.id = ca.category_id
                     LEFT JOIN player_guardians pg ON pg.player_id = p.id AND pg.is_primary AND pg.valid_to IS NULL
                     LEFT JOIN guardians g ON g.id = pg.guardian_id
                     LEFT JOIN users gu ON gu.id = g.user_id
            WHERE cb.status IN ('PENDING', 'PARTIAL')
            """;

    private final JdbcTemplate jdbc;

    public JdbcReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReceivableLine> receivables(UUID seasonId, UUID categoryId, LocalDate asOf, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id AS player_id, p.code AS player_code, p.first_name || ' ' || p.last_name AS player_name,
                       c.id AS category_id, coalesce(c.name, 'Sin categoría') AS category_name,
                       gu.full_name AS guardian_name, f.concept, f.name AS fee_name, cb.period, cb.due_date,
                       cb.net_amount, cb.applied, cb.outstanding, cb.status
                """).append(RECEIVABLES_FROM);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, seasonId, categoryId);
        sql.append(" ORDER BY category_name, cb.due_date, p.last_name, p.first_name, cb.charge_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            LocalDate due = rs.getObject("due_date", LocalDate.class);
            return new ReceivableLine(rs.getObject("player_id", UUID.class), rs.getString("player_code"),
                    rs.getString("player_name"), rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                    rs.getString("guardian_name"), rs.getString("concept"), rs.getString("fee_name"),
                    rs.getObject("period", LocalDate.class), due, rs.getBigDecimal("net_amount"),
                    rs.getBigDecimal("applied"), rs.getBigDecimal("outstanding"), ReportRules.daysOverdue(due, asOf),
                    rs.getString("status"));
        }, args.toArray());
    }

    @Override
    public List<CategoryReceivable> receivableTotals(UUID seasonId, UUID categoryId, LocalDate asOf) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id AS category_id, coalesce(c.name, 'Sin categoría') AS category_name,
                       count(DISTINCT p.id) AS players, sum(cb.net_amount) AS charged, sum(cb.applied) AS applied,
                       sum(cb.outstanding) AS outstanding,
                       coalesce(sum(cb.outstanding) FILTER (WHERE cb.due_date < ?), 0) AS overdue
                """).append(RECEIVABLES_FROM);
        List<Object> args = new ArrayList<>();
        args.add(asOf);
        appendFilters(sql, args, seasonId, categoryId);
        sql.append(" GROUP BY c.id, c.name ORDER BY category_name");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new CategoryReceivable(rs.getObject("category_id", UUID.class),
                rs.getString("category_name"), rs.getInt("players"), rs.getBigDecimal("charged"),
                rs.getBigDecimal("applied"), rs.getBigDecimal("outstanding"), rs.getBigDecimal("overdue")), args.toArray());
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, UUID seasonId, UUID categoryId) {
        if (seasonId != null) {
            sql.append(" AND f.season_id = ?");
            args.add(seasonId);
        }
        if (categoryId != null) {
            sql.append(" AND ca.category_id = ?");
            args.add(categoryId);
        }
    }

    @Override
    public boolean seasonExists(UUID seasonId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM seasons WHERE id = ?)", Boolean.class, seasonId));
    }

    @Override
    public boolean categoryExists(UUID categoryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM categories WHERE id = ?)", Boolean.class, categoryId));
    }

    @Override
    public Optional<SeasonSummary> seasonSummary(UUID seasonId, LocalDate today, Instant generatedAt) {
        List<SeasonSummary.Season> found = jdbc.query(
                "SELECT id, name, status, start_at, end_at FROM seasons WHERE id = ?",
                (rs, rowNum) -> new SeasonSummary.Season(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("status"), rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant()), seasonId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SeasonSummary.Season season = found.get(0);
        OffsetDateTime start = OffsetDateTime.ofInstant(season.startAt(), ZoneOffset.UTC);
        OffsetDateTime end = OffsetDateTime.ofInstant(season.endAt(), ZoneOffset.UTC);

        List<SeasonSummary.RosterLine> roster = jdbc.query("""
                SELECT c.id, c.name, c.capacity, count(DISTINCT ca.player_id) AS players
                FROM categories c
                         LEFT JOIN category_assignments ca ON ca.category_id = c.id
                    AND ca.valid_from < ? AND (ca.valid_to IS NULL OR ca.valid_to > ?)
                GROUP BY c.id, c.name, c.capacity
                HAVING c.active OR count(ca.id) > 0
                ORDER BY c.name
                """, (rs, rowNum) -> new SeasonSummary.RosterLine(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getInt("capacity"), rs.getInt("players")), end, start);

        List<SeasonSummary.EventCount> events = jdbc.query("""
                SELECT event_type, status, count(*) AS total
                FROM sports_events
                WHERE start_at >= ? AND start_at < ?
                GROUP BY event_type, status
                ORDER BY event_type, status
                """, (rs, rowNum) -> new SeasonSummary.EventCount(rs.getString("event_type"), rs.getString("status"),
                rs.getInt("total")), start, end);

        SeasonSummary.Attendance attendance = jdbc.queryForObject("""
                SELECT count(DISTINCT e.id)                                                     AS sessions,
                       count(r.id) FILTER (WHERE r.status = 'PRESENT')                          AS present,
                       count(r.id) FILTER (WHERE r.status = 'LATE')                             AS late,
                       count(r.id) FILTER (WHERE r.status = 'EXCUSED')                          AS excused,
                       count(r.id) FILTER (WHERE r.status = 'EXCUSED' AND r.excuse_accepted)    AS accepted,
                       count(r.id) FILTER (WHERE r.status = 'ABSENT')                           AS absent
                FROM attendance_sessions s
                         JOIN sports_events e ON e.id = s.event_id
                         LEFT JOIN attendance_records r ON r.event_id = e.id
                WHERE s.closed_at IS NOT NULL AND e.status <> 'CANCELLED' AND e.start_at >= ? AND e.start_at < ?
                """, (rs, rowNum) -> {
            int present = rs.getInt("present");
            int late = rs.getInt("late");
            int excused = rs.getInt("excused");
            int accepted = rs.getInt("accepted");
            int absent = rs.getInt("absent");
            return new SeasonSummary.Attendance(rs.getInt("sessions"), present, late, excused, accepted, absent,
                    ReportRules.attendancePercentage(present + late, present + late + excused + absent, accepted));
        }, start, end);

        SeasonSummary.Matches matches = jdbc.queryForObject("""
                SELECT count(*)                                           AS played,
                       count(*) FILTER (WHERE m.score_for > m.score_against)  AS wins,
                       count(*) FILTER (WHERE m.score_for = m.score_against)  AS draws,
                       count(*) FILTER (WHERE m.score_for < m.score_against)  AS losses,
                       coalesce(sum(m.score_for), 0)                      AS goals_for,
                       coalesce(sum(m.score_against), 0)                  AS goals_against
                FROM matches m
                         JOIN sports_events e ON e.id = m.event_id
                WHERE e.status = 'COMPLETED' AND m.score_for IS NOT NULL AND e.start_at >= ? AND e.start_at < ?
                """, (rs, rowNum) -> new SeasonSummary.Matches(rs.getInt("played"), rs.getInt("wins"), rs.getInt("draws"),
                rs.getInt("losses"), rs.getInt("goals_for"), rs.getInt("goals_against")), start, end);

        SeasonSummary.Billing billing = jdbc.queryForObject("""
                SELECT coalesce(sum(cb.net_amount), 0)  AS charged,
                       coalesce(sum(cb.applied), 0)     AS applied,
                       coalesce(sum(cb.outstanding), 0) AS outstanding,
                       coalesce(sum(cb.outstanding) FILTER (WHERE cb.due_date < ? AND cb.outstanding > 0), 0) AS overdue
                FROM charge_balances cb
                         JOIN fees f ON f.id = cb.fee_id
                WHERE f.season_id = ? AND cb.status <> 'VOIDED'
                """, (rs, rowNum) -> new SeasonSummary.Billing(rs.getBigDecimal("charged"), rs.getBigDecimal("applied"),
                rs.getBigDecimal("outstanding"), rs.getBigDecimal("overdue")), today, seasonId);

        return Optional.of(new SeasonSummary(season, generatedAt, roster, events, attendance, matches, billing));
    }
}
