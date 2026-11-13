package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.reports.application.ClubOverview;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore;

/**
 * Lectura del tablero del club con SQL de solo lectura sobre tablas y vistas de otros módulos, sin depender de sus clases
 * (mismo criterio que {@link JdbcReportStore}). Todo valor externo viaja como parámetro.
 */
@Repository
public class JdbcDashboardStore implements DashboardStore {

    private static final int EVENT_LIMIT = 500;

    private final JdbcTemplate jdbc;

    public JdbcDashboardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TeamBase> teams() {
        return jdbc.query("""
                SELECT c.id, c.name, coach.id AS coach_id, coach.full_name AS coach
                FROM categories c
                         LEFT JOIN LATERAL (
                    SELECT u.id, u.full_name
                    FROM category_assignments ca
                             JOIN users u ON u.id = ca.head_coach_id
                    WHERE ca.category_id = c.id AND ca.valid_to IS NULL
                    GROUP BY u.id, u.full_name
                    ORDER BY count(*) DESC, u.full_name, u.id
                    LIMIT 1) coach ON true
                WHERE c.active
                ORDER BY c.name, c.id
                """, (rs, n) -> new TeamBase(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("coach_id", UUID.class), rs.getString("coach")));
    }

    @Override
    public List<PlayerRow> players(LocalDate today) {
        return jdbc.query("""
                SELECT p.id, ca.category_id, p.code, p.first_name || ' ' || p.last_name AS full_name, p.status,
                       coalesce(att.attended, 0) AS attended, coalesce(att.counted, 0) AS counted,
                       coalesce(od.overdue, false) AS overdue,
                       pc.charge_id, pc.concept, pc.fee_name, pc.period, pc.due_date, pc.outstanding
                FROM players p
                         JOIN category_assignments ca ON ca.player_id = p.id AND ca.valid_to IS NULL
                         LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE ar.status IN ('PRESENT', 'LATE')) AS attended,
                           count(*) FILTER (WHERE NOT (ar.status = 'EXCUSED' AND ar.excuse_accepted)) AS counted
                    FROM attendance_records ar
                             JOIN attendance_sessions s ON s.event_id = ar.event_id AND s.closed_at IS NOT NULL
                             JOIN sports_events e ON e.id = ar.event_id AND e.status <> 'CANCELLED'
                    WHERE ar.player_id = p.id) att ON true
                         LEFT JOIN LATERAL (
                    SELECT true AS overdue
                    FROM charge_balances cb
                    WHERE cb.player_id = p.id AND cb.status IN ('PENDING', 'PARTIAL') AND cb.due_date < ?
                    LIMIT 1) od ON true
                         LEFT JOIN LATERAL (
                    SELECT cb.charge_id, f.concept, f.name AS fee_name, cb.period, cb.due_date, cb.outstanding
                    FROM charge_balances cb
                             JOIN fees f ON f.id = cb.fee_id
                    WHERE cb.player_id = p.id AND cb.status IN ('PENDING', 'PARTIAL')
                    ORDER BY cb.due_date, cb.charge_id
                    LIMIT 1) pc ON true
                WHERE p.status IN ('ACTIVE', 'IN_EVALUATION', 'SUSPENDED')
                ORDER BY ca.category_id, p.last_name, p.first_name, p.id
                """, (rs, n) -> {
            UUID chargeId = rs.getObject("charge_id", UUID.class);
            ClubOverview.PendingCharge pending = chargeId == null ? null : new ClubOverview.PendingCharge(chargeId,
                    rs.getString("concept"), rs.getString("fee_name"), rs.getObject("period", LocalDate.class),
                    rs.getObject("due_date", LocalDate.class), rs.getBigDecimal("outstanding"));
            return new PlayerRow(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
                    rs.getString("code"), rs.getString("full_name"), rs.getString("status"), rs.getLong("attended"),
                    rs.getLong("counted"), rs.getBoolean("overdue"), pending);
        }, today);
    }

    @Override
    public List<SessionRow> closedSessions(Instant since) {
        return jdbc.query("""
                SELECT e.category_id, e.id AS event_id, e.start_at,
                       count(*) FILTER (WHERE ar.status IN ('PRESENT', 'LATE')) AS attended,
                       count(*) FILTER (WHERE NOT (ar.status = 'EXCUSED' AND ar.excuse_accepted)) AS counted
                FROM sports_events e
                         JOIN attendance_sessions s ON s.event_id = e.id AND s.closed_at IS NOT NULL
                         JOIN attendance_records ar ON ar.event_id = e.id
                WHERE e.status <> 'CANCELLED' AND e.start_at >= ?
                GROUP BY e.category_id, e.id, e.start_at
                ORDER BY e.start_at, e.id
                """, (rs, n) -> new SessionRow(rs.getObject("category_id", UUID.class), rs.getObject("event_id", UUID.class),
                instant(rs, "start_at"), rs.getLong("attended"), rs.getLong("counted")), OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC));
    }

    @Override
    public List<MoneyRow> money(Instant from, Instant to, LocalDate fromDate, LocalDate toDate) {
        return jdbc.query("""
                SELECT c.id AS category_id,
                       coalesce(rev.total, 0) AS revenue,
                       coalesce(debt.total, 0) AS debt,
                       coalesce(per.charged, 0) AS charged,
                       coalesce(per.applied, 0) AS applied
                FROM categories c
                         LEFT JOIN (SELECT ca.category_id, sum(pm.amount + coalesce(a.adjusted, 0)) AS total
                                    FROM payments pm
                                             JOIN category_assignments ca ON ca.player_id = pm.player_id AND ca.valid_to IS NULL
                                             LEFT JOIN (SELECT payment_id, sum(amount) AS adjusted
                                                        FROM adjustments
                                                        GROUP BY payment_id) a ON a.payment_id = pm.id
                                    WHERE pm.paid_at >= ? AND pm.paid_at < ?
                                    GROUP BY ca.category_id) rev ON rev.category_id = c.id
                         LEFT JOIN (SELECT ca.category_id, sum(cb.outstanding) AS total
                                    FROM charge_balances cb
                                             JOIN category_assignments ca ON ca.player_id = cb.player_id AND ca.valid_to IS NULL
                                    WHERE cb.status IN ('PENDING', 'PARTIAL')
                                    GROUP BY ca.category_id) debt ON debt.category_id = c.id
                         LEFT JOIN (SELECT ca.category_id, sum(cb.net_amount) AS charged,
                                           sum(least(cb.applied, cb.net_amount)) AS applied
                                    FROM charge_balances cb
                                             JOIN category_assignments ca ON ca.player_id = cb.player_id AND ca.valid_to IS NULL
                                    WHERE cb.status <> 'VOIDED' AND cb.due_date BETWEEN ? AND ?
                                    GROUP BY ca.category_id) per ON per.category_id = c.id
                WHERE c.active
                """, (rs, n) -> new MoneyRow(rs.getObject("category_id", UUID.class), rs.getBigDecimal("revenue"),
                rs.getBigDecimal("debt"), rs.getBigDecimal("charged"), rs.getBigDecimal("applied")),
                OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC), OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC),
                fromDate, toDate);
    }

    @Override
    public List<NextMatchRow> nextMatches(Instant now) {
        return jdbc.query("""
                SELECT DISTINCT ON (e.category_id) e.category_id, e.id AS event_id, e.start_at, m.opponent, v.name AS venue
                FROM sports_events e
                         JOIN matches m ON m.event_id = e.id
                         JOIN venues v ON v.id = e.venue_id
                WHERE e.status = 'CONFIRMED' AND e.start_at >= ?
                ORDER BY e.category_id, e.start_at, e.id
                """, (rs, n) -> new NextMatchRow(rs.getObject("category_id", UUID.class), rs.getObject("event_id", UUID.class),
                instant(rs, "start_at"), rs.getString("opponent"), rs.getString("venue")),
                OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
    }

    @Override
    public List<ClubOverview.Event> events(Instant from, Instant to) {
        return jdbc.query("""
                SELECT e.id, e.category_id, e.event_type, e.status, e.start_at, e.end_at, v.name AS venue, m.opponent
                FROM sports_events e
                         JOIN venues v ON v.id = e.venue_id
                         LEFT JOIN matches m ON m.event_id = e.id
                WHERE e.status <> 'CANCELLED' AND e.start_at >= ? AND e.start_at < ?
                ORDER BY e.start_at, e.id
                LIMIT ?
                """, (rs, n) -> new ClubOverview.Event(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
                rs.getString("event_type"), rs.getString("status"), instant(rs, "start_at"), instant(rs, "end_at"),
                rs.getString("venue"), rs.getString("opponent")),
                OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC), OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC),
                EVENT_LIMIT);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
