package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.reports.application.SeasonClosure;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReport;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReportFacts;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReportStore;
import co.edu.unipamplona.canchatrack.reports.domain.ReportRules;

/**
 * Almacén del informe de temporada. Los datos deportivos se leen con SQL de solo lectura sobre tablas de otros módulos
 * (modelo de lectura, sin depender de sus clases); solo se escriben {@code season_reports} y {@code season_closures}.
 * Todo valor externo viaja como parámetro.
 */
@Repository
public class JdbcSeasonReportStore implements SeasonReportStore {

    private static final String REPORT_COLUMNS = """
            id, player_id, season_id, consecutive, version, previous_report_id, reason, verification_code, template_version,
            file_id, file_hash, generated_at, generated_by
            """;
    private static final String CLOSURE_COLUMNS = """
            id, player_id, season_id, report_id, closed_by, closed_at, reopened_by, reopened_at, reopen_reason, reopen_detail
            """;

    private static final RowMapper<SeasonReport> REPORT = (rs, rowNum) -> new SeasonReport(
            rs.getObject("id", UUID.class), rs.getObject("player_id", UUID.class), rs.getObject("season_id", UUID.class),
            rs.getLong("consecutive"), ReportRules.reportNumber(rs.getLong("consecutive")), rs.getInt("version"),
            rs.getObject("previous_report_id", UUID.class), rs.getString("reason"), rs.getString("verification_code"),
            rs.getString("template_version"), rs.getObject("file_id", UUID.class), rs.getString("file_hash"),
            instant(rs, "generated_at"), rs.getObject("generated_by", UUID.class));

    private static final RowMapper<SeasonClosure> CLOSURE = (rs, rowNum) -> new SeasonClosure(
            rs.getObject("id", UUID.class), rs.getObject("player_id", UUID.class), rs.getObject("season_id", UUID.class),
            rs.getObject("report_id", UUID.class), rs.getObject("closed_by", UUID.class), instant(rs, "closed_at"),
            rs.getObject("reopened_by", UUID.class), instant(rs, "reopened_at"), rs.getString("reopen_reason"),
            rs.getString("reopen_detail"));

    private final JdbcTemplate jdbc;

    public JdbcSeasonReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    // ---------- datos deportivos (solo lectura) ----------

    @Override
    public Optional<SeasonReportFacts.Season> findSeason(UUID seasonId) {
        return jdbc.query("SELECT id, name, status, start_at, end_at FROM seasons WHERE id = ?",
                (rs, rowNum) -> new SeasonReportFacts.Season(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("status"), instant(rs, "start_at"), instant(rs, "end_at")), seasonId).stream().findFirst();
    }

    @Override
    public Optional<SeasonReportFacts.Player> findPlayer(UUID playerId) {
        return jdbc.query("""
                SELECT p.id, p.code, p.first_name || ' ' || p.last_name AS full_name, p.birth_date, p.status,
                       c.name AS category_name, coach.full_name AS coach_name
                FROM players p
                         LEFT JOIN LATERAL (SELECT ca.category_id, ca.head_coach_id
                                            FROM category_assignments ca
                                            WHERE ca.player_id = p.id
                                            ORDER BY ca.valid_from DESC
                                            LIMIT 1) last_assignment ON true
                         LEFT JOIN categories c ON c.id = last_assignment.category_id
                         LEFT JOIN users coach ON coach.id = last_assignment.head_coach_id
                WHERE p.id = ?
                """, (rs, rowNum) -> new SeasonReportFacts.Player(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("full_name"), rs.getObject("birth_date", LocalDate.class), rs.getString("status"),
                rs.getString("category_name"), rs.getString("coach_name")), playerId).stream().findFirst();
    }

    @Override
    public SeasonReportFacts.Attendance attendance(UUID playerId, Instant from, Instant to) {
        return jdbc.queryForObject("""
                SELECT count(*)                                                       AS sessions,
                       count(*) FILTER (WHERE r.status = 'PRESENT')                   AS present,
                       count(*) FILTER (WHERE r.status = 'LATE')                      AS late,
                       count(*) FILTER (WHERE r.status = 'EXCUSED')                   AS excused,
                       count(*) FILTER (WHERE r.status = 'EXCUSED' AND r.excuse_accepted) AS accepted,
                       count(*) FILTER (WHERE r.status = 'ABSENT')                    AS absent
                FROM attendance_records r
                         JOIN sports_events e ON e.id = r.event_id
                         JOIN attendance_sessions s ON s.event_id = e.id AND s.closed_at IS NOT NULL
                WHERE r.player_id = ? AND e.status <> 'CANCELLED' AND e.start_at >= ? AND e.start_at < ?
                """, (rs, rowNum) -> {
            int present = rs.getInt("present");
            int late = rs.getInt("late");
            int excused = rs.getInt("excused");
            int accepted = rs.getInt("accepted");
            int absent = rs.getInt("absent");
            return new SeasonReportFacts.Attendance(rs.getInt("sessions"), present, late, excused, accepted, absent,
                    ReportRules.attendancePercentage(present + late, present + late + excused + absent, accepted));
        }, playerId, offset(from), offset(to));
    }

    @Override
    public List<SeasonReportFacts.Evaluation> evaluations(UUID playerId, Instant from, Instant to) {
        record Round(UUID id, Instant closedAt, LocalDate start, LocalDate end, String result, String plan) {
        }
        List<Round> rounds = jdbc.query("""
                SELECT id, closed_at, period_start, period_end, result, follow_up_plan
                FROM evaluation_rounds
                WHERE player_id = ? AND status = 'CLOSED' AND closed_at >= ? AND closed_at < ?
                ORDER BY closed_at, id
                """, (rs, rowNum) -> new Round(rs.getObject("id", UUID.class), instant(rs, "closed_at"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                rs.getString("result"), rs.getString("follow_up_plan")), playerId, offset(from), offset(to));

        List<SeasonReportFacts.Evaluation> result = new ArrayList<>();
        for (Round round : rounds) {
            // Solo cuentan los conceptos emitidos sobre el periodo vigente de la ronda (un cambio de periodo invalida los anteriores).
            List<SeasonReportFacts.Concept> concepts = jdbc.query("""
                    SELECT u.full_name, d.verdict
                    FROM evaluator_decisions d
                             JOIN users u ON u.id = d.evaluator_id
                    WHERE d.round_id = ? AND d.period_start = ? AND d.period_end = ?
                    ORDER BY u.full_name, d.id
                    """, (rs, rowNum) -> new SeasonReportFacts.Concept(rs.getString("full_name"), rs.getString("verdict")),
                    round.id(), round.start(), round.end());
            result.add(new SeasonReportFacts.Evaluation(round.closedAt(), round.start(), round.end(), round.result(),
                    round.plan(), concepts));
        }
        return result;
    }

    @Override
    public List<SeasonReportFacts.MatchLine> matches(UUID playerId, Instant from, Instant to) {
        return jdbc.query("""
                SELECT e.start_at, m.opponent, t.name AS tournament, m.score_for, m.score_against,
                       ce.minutes_played, ce.goals
                FROM callup_entries ce
                         JOIN matches m ON m.id = ce.match_id
                         JOIN sports_events e ON e.id = m.event_id
                         LEFT JOIN tournaments t ON t.id = m.tournament_id
                WHERE ce.player_id = ? AND ce.status = 'CONFIRMED' AND e.status = 'COMPLETED'
                  AND e.start_at >= ? AND e.start_at < ?
                ORDER BY e.start_at, m.id
                """, (rs, rowNum) -> new SeasonReportFacts.MatchLine(instant(rs, "start_at"), rs.getString("opponent"),
                rs.getString("tournament"), nullableInt(rs, "score_for"), nullableInt(rs, "score_against"),
                nullableInt(rs, "minutes_played"), nullableInt(rs, "goals")), playerId, offset(from), offset(to));
    }

    @Override
    public boolean changedSince(UUID playerId, Instant since) {
        OffsetDateTime at = offset(since);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM attendance_records WHERE player_id = ? AND recorded_at > ?)
                    OR EXISTS (SELECT 1
                               FROM attendance_corrections c
                                        JOIN attendance_records r ON r.id = c.record_id
                               WHERE r.player_id = ? AND c.corrected_at > ?)
                    OR EXISTS (SELECT 1
                               FROM attendance_sessions s
                                        JOIN attendance_records r ON r.event_id = s.event_id
                               WHERE r.player_id = ? AND s.closed_at > ?)
                    OR EXISTS (SELECT 1
                               FROM evaluation_rounds
                               WHERE player_id = ? AND status = 'CLOSED' AND closed_at > ?)
                    OR EXISTS (SELECT 1
                               FROM callup_entries ce
                                        JOIN matches m ON m.id = ce.match_id
                               WHERE ce.player_id = ? AND ce.status = 'CONFIRMED' AND m.result_recorded_at > ?)
                """, Boolean.class, playerId, at, playerId, at, playerId, at, playerId, at, playerId, at));
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    // ---------- informes ----------

    @Override
    public void lockForGeneration(UUID playerId, UUID seasonId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, "season-report:" + playerId + ":" + seasonId);
    }

    @Override
    public long nextConsecutive() {
        Long value = jdbc.queryForObject("SELECT nextval('season_report_consecutive_seq')", Long.class);
        return value == null ? 0 : value;
    }

    @Override
    public Optional<SeasonReport> latest(UUID playerId, UUID seasonId) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM season_reports WHERE player_id = ? AND season_id = ? "
                + "ORDER BY version DESC LIMIT 1", REPORT, playerId, seasonId).stream().findFirst();
    }

    @Override
    public Optional<SeasonReport> find(UUID reportId) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM season_reports WHERE id = ?", REPORT, reportId).stream().findFirst();
    }

    @Override
    public Optional<SeasonReport> findByVerificationCode(String code) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM season_reports WHERE verification_code = ?", REPORT, code)
                .stream().findFirst();
    }

    @Override
    public List<SeasonReport> list(UUID playerId, UUID seasonId) {
        StringBuilder sql = new StringBuilder("SELECT " + REPORT_COLUMNS + " FROM season_reports WHERE player_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(playerId);
        if (seasonId != null) {
            sql.append(" AND season_id = ?");
            args.add(seasonId);
        }
        sql.append(" ORDER BY generated_at DESC, version DESC, id");
        return jdbc.query(sql.toString(), REPORT, args.toArray());
    }

    @Override
    public SeasonReport insert(NewReport r) {
        return jdbc.queryForObject("INSERT INTO season_reports (player_id, season_id, consecutive, version, previous_report_id, "
                        + "reason, verification_code, template_version, snapshot, file_id, file_hash, generated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING " + REPORT_COLUMNS, REPORT,
                r.playerId(), r.seasonId(), r.consecutive(), r.version(), r.previousReportId(), r.reason(),
                r.verificationCode(), r.templateVersion(), r.snapshotJson(), r.fileId(), r.fileHash(), r.generatedBy());
    }

    // ---------- cierres ----------

    @Override
    public SeasonClosure insertClosure(UUID playerId, UUID seasonId, UUID reportId, UUID closedBy) {
        return jdbc.queryForObject("INSERT INTO season_closures (player_id, season_id, report_id, closed_by) "
                + "VALUES (?, ?, ?, ?) RETURNING " + CLOSURE_COLUMNS, CLOSURE, playerId, seasonId, reportId, closedBy);
    }

    @Override
    public Optional<SeasonClosure> latestOpenClosure(UUID playerId) {
        return jdbc.query("SELECT " + CLOSURE_COLUMNS + " FROM season_closures WHERE player_id = ? AND reopened_at IS NULL "
                + "ORDER BY closed_at DESC, id LIMIT 1", CLOSURE, playerId).stream().findFirst();
    }

    @Override
    public Optional<SeasonClosure> latestClosure(UUID playerId) {
        return jdbc.query("SELECT " + CLOSURE_COLUMNS + " FROM season_closures WHERE player_id = ? "
                + "ORDER BY closed_at DESC, id LIMIT 1", CLOSURE, playerId).stream().findFirst();
    }

    @Override
    public SeasonClosure markReopened(UUID closureId, UUID reopenedBy, String reason, String detail) {
        return jdbc.queryForObject("UPDATE season_closures SET reopened_at = now(), reopened_by = ?, reopen_reason = ?, "
                + "reopen_detail = ? WHERE id = ? AND reopened_at IS NULL RETURNING " + CLOSURE_COLUMNS, CLOSURE,
                reopenedBy, reason, detail, closureId);
    }
}
