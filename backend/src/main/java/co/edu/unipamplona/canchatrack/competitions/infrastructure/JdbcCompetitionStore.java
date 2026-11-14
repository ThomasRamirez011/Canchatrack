package co.edu.unipamplona.canchatrack.competitions.infrastructure;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import co.edu.unipamplona.canchatrack.competitions.application.CompetitionStore;
import co.edu.unipamplona.canchatrack.competitions.application.DuplicateTournamentException;
import co.edu.unipamplona.canchatrack.competitions.application.ReferenceNotFoundException;
import co.edu.unipamplona.canchatrack.competitions.application.Views.CallupRow;
import co.edu.unipamplona.canchatrack.competitions.application.Views.MatchView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.PlayerMatchView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.TournamentView;
import co.edu.unipamplona.canchatrack.competitions.domain.PlayerResultLine;

@Repository
public class JdbcCompetitionStore implements CompetitionStore {

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Boolean>> CHECKS = new TypeReference<>() { };

    private static final String TOURNAMENT_SELECT = """
            SELECT id, season_id, name, start_date, end_date, category_id, venue_id, rules FROM tournaments
            """;

    private static final RowMapper<TournamentView> TOURNAMENT = (rs, rowNum) -> new TournamentView(
            rs.getObject("id", UUID.class), rs.getObject("season_id", UUID.class), rs.getString("name"),
            rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
            rs.getObject("category_id", UUID.class), rs.getObject("venue_id", UUID.class), rs.getString("rules"));

    /** Proyección de solo lectura del evento y el escenario; el evento se escribe únicamente vía scheduling.api. */
    private static final String MATCH_SELECT = """
            SELECT m.id, m.event_id, m.tournament_id, t.name AS tournament_name, e.category_id, e.venue_id,
                   v.name AS venue_name, hc.user_id AS head_coach_id, m.opponent, e.start_at, e.end_at, e.meeting_at,
                   e.meeting_point, e.instructions, e.status, e.cancellation_reason, m.score_for, m.score_against,
                   m.result_notes, m.result_recorded_at
            FROM matches m
                     JOIN sports_events e ON e.id = m.event_id
                     JOIN venues v ON v.id = e.venue_id
                     LEFT JOIN tournaments t ON t.id = m.tournament_id
                     LEFT JOIN event_participants hc ON hc.event_id = e.id AND hc.role = 'HEAD_COACH'
            """;

    private static final RowMapper<MatchView> MATCH = (rs, rowNum) -> new MatchView(rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class), rs.getObject("tournament_id", UUID.class), rs.getString("tournament_name"),
            rs.getObject("category_id", UUID.class), rs.getObject("venue_id", UUID.class), rs.getString("venue_name"),
            rs.getObject("head_coach_id", UUID.class), rs.getString("opponent"),
            instant(rs.getObject("start_at", OffsetDateTime.class)), instant(rs.getObject("end_at", OffsetDateTime.class)),
            instant(rs.getObject("meeting_at", OffsetDateTime.class)), rs.getString("meeting_point"),
            rs.getString("instructions"), rs.getString("status"), rs.getString("cancellation_reason"),
            rs.getObject("score_for", Integer.class), rs.getObject("score_against", Integer.class),
            rs.getString("result_notes"), instant(rs.getObject("result_recorded_at", OffsetDateTime.class)));

    private static final String CALLUP_SELECT = """
            SELECT player_id, status, ineligibility_code, eligibility::text AS eligibility, proposed_at, confirmed_at,
                   minutes_played, goals, observations
            FROM callup_entries
            """;

    private static final RowMapper<CallupRow> CALLUP = (rs, rowNum) -> new CallupRow(rs.getObject("player_id", UUID.class),
            rs.getString("status"), rs.getString("ineligibility_code"), checks(rs.getString("eligibility")),
            instant(rs.getObject("proposed_at", OffsetDateTime.class)), instant(rs.getObject("confirmed_at", OffsetDateTime.class)),
            rs.getObject("minutes_played", Integer.class), rs.getObject("goals", Integer.class), rs.getString("observations"));

    private final JdbcTemplate jdbc;

    public JdbcCompetitionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Map<String, Boolean> checks(String json) {
        try {
            return json == null ? Map.of() : MAPPER.readValue(json, CHECKS);
        } catch (JacksonException e) {
            throw new IllegalStateException("callup_entries.eligibility is not a JSON object of booleans", e);
        }
    }

    private static String write(Map<String, Boolean> checks) {
        try {
            return MAPPER.writeValueAsString(checks);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isForeignKeyViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState());
    }

    @Override
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    // ---------- torneos ----------

    @Override
    public UUID insertTournament(UUID seasonId, String name, LocalDate startDate, LocalDate endDate, UUID categoryId,
                                 UUID venueId, String rules, UUID createdBy) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO tournaments (season_id, name, start_date, end_date, category_id, venue_id, rules, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, UUID.class, seasonId, name, startDate, endDate, categoryId, venueId, rules, createdBy);
        } catch (DuplicateKeyException e) {
            throw new DuplicateTournamentException();
        } catch (DataIntegrityViolationException e) {
            if (isForeignKeyViolation(e)) {
                throw new ReferenceNotFoundException();
            }
            throw e;
        }
    }

    @Override
    public Optional<TournamentView> findTournament(UUID tournamentId) {
        return jdbc.query(TOURNAMENT_SELECT + " WHERE id = ?", TOURNAMENT, tournamentId).stream().findFirst();
    }

    @Override
    public List<TournamentView> findTournaments(UUID seasonId) {
        return jdbc.query(TOURNAMENT_SELECT + " WHERE (?::uuid IS NULL OR season_id = ?::uuid) ORDER BY start_date NULLS LAST, name",
                TOURNAMENT, seasonId, seasonId);
    }

    // ---------- partidos ----------

    @Override
    public UUID insertMatch(UUID eventId, UUID tournamentId, String opponent, UUID createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO matches (event_id, tournament_id, opponent, created_by) VALUES (?, ?, ?, ?) RETURNING id
                """, UUID.class, eventId, tournamentId, opponent, createdBy);
    }

    @Override
    public Optional<MatchView> findMatch(UUID matchId) {
        return jdbc.query(MATCH_SELECT + " WHERE m.id = ?", MATCH, matchId).stream().findFirst();
    }

    @Override
    public Optional<MatchView> lockMatch(UUID matchId) {
        List<UUID> locked = jdbc.queryForList("SELECT id FROM matches WHERE id = ? FOR UPDATE", UUID.class, matchId);
        return locked.isEmpty() ? Optional.empty() : findMatch(matchId);
    }

    @Override
    public List<MatchView> findMatches(UUID categoryId, UUID tournamentId, Instant from, Instant to, UUID coachId, int limit) {
        return jdbc.query(MATCH_SELECT + """
                 WHERE (?::uuid IS NULL OR e.category_id = ?::uuid)
                   AND (?::uuid IS NULL OR m.tournament_id = ?::uuid)
                   AND (?::timestamptz IS NULL OR e.start_at >= ?::timestamptz)
                   AND (?::timestamptz IS NULL OR e.start_at < ?::timestamptz)
                   AND (?::uuid IS NULL OR hc.user_id = ?::uuid)
                 ORDER BY e.start_at, m.id
                 LIMIT ?
                """, MATCH, categoryId, categoryId, tournamentId, tournamentId, utc(from), utc(from), utc(to), utc(to),
                coachId, coachId, limit);
    }

    // ---------- convocatoria ----------

    @Override
    public List<CallupRow> callupsOf(UUID matchId) {
        return jdbc.query(CALLUP_SELECT + " WHERE match_id = ? AND status <> 'REMOVED' ORDER BY proposed_at, player_id",
                CALLUP, matchId);
    }

    @Override
    public List<UUID> confirmedPlayersOfEvent(UUID eventId) {
        return jdbc.query("""
                SELECT c.player_id
                FROM callup_entries c
                         JOIN matches m ON m.id = c.match_id
                WHERE m.event_id = ? AND c.status = 'CONFIRMED'
                ORDER BY c.player_id
                """, (rs, rowNum) -> rs.getObject("player_id", UUID.class), eventId);
    }

    @Override
    public Map<UUID, CallupRow> callupsOf(UUID matchId, Collection<UUID> playerIds) {
        Map<UUID, CallupRow> result = new LinkedHashMap<>();
        if (playerIds.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>();
        args.add(matchId);
        args.addAll(playerIds);
        String placeholders = String.join(", ", Collections.nCopies(playerIds.size(), "?"));
        jdbc.query(CALLUP_SELECT + " WHERE match_id = ? AND player_id IN (" + placeholders + ")", CALLUP, args.toArray())
                .forEach(row -> result.put(row.playerId(), row));
        return result;
    }

    @Override
    public void upsertProposal(UUID matchId, UUID playerId, String status, Map<String, Boolean> eligibility,
                               String ineligibilityCode, UUID proposedBy) {
        try {
            jdbc.update("""
                    INSERT INTO callup_entries (match_id, player_id, status, eligibility, ineligibility_code, proposed_by)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (match_id, player_id) DO UPDATE
                        SET status = EXCLUDED.status,
                            eligibility = EXCLUDED.eligibility,
                            ineligibility_code = EXCLUDED.ineligibility_code,
                            proposed_by = EXCLUDED.proposed_by,
                            proposed_at = now(),
                            confirmed_by = NULL,
                            confirmed_at = NULL
                        WHERE callup_entries.status <> 'CONFIRMED'
                    """, matchId, playerId, status, write(eligibility), ineligibilityCode, proposedBy);
        } catch (DataIntegrityViolationException e) {
            if (isForeignKeyViolation(e)) {
                throw new ReferenceNotFoundException();
            }
            throw e;
        }
    }

    @Override
    public void markRemoved(UUID matchId, UUID playerId) {
        jdbc.update("UPDATE callup_entries SET status = 'REMOVED', confirmed_by = NULL, confirmed_at = NULL WHERE match_id = ? AND player_id = ?",
                matchId, playerId);
    }

    @Override
    public void confirm(UUID matchId, UUID playerId, Map<String, Boolean> eligibility, UUID confirmedBy) {
        jdbc.update("""
                UPDATE callup_entries
                SET status = 'CONFIRMED', eligibility = ?::jsonb, ineligibility_code = NULL, confirmed_by = ?, confirmed_at = now()
                WHERE match_id = ? AND player_id = ? AND status = 'PROPOSED'
                """, write(eligibility), confirmedBy, matchId, playerId);
    }

    // ---------- resultado e historial ----------

    @Override
    public void saveResult(UUID matchId, int scoreFor, int scoreAgainst, String notes, UUID recordedBy) {
        jdbc.update("""
                UPDATE matches
                SET score_for = ?, score_against = ?, result_notes = ?, result_recorded_by = ?, result_recorded_at = now()
                WHERE id = ?
                """, scoreFor, scoreAgainst, notes, recordedBy, matchId);
    }

    @Override
    public void saveParticipation(UUID matchId, PlayerResultLine line) {
        jdbc.update("""
                UPDATE callup_entries SET minutes_played = ?, goals = ?, observations = ?
                WHERE match_id = ? AND player_id = ? AND status = 'CONFIRMED'
                """, line.minutesPlayed(), line.goals(), line.observations(), matchId, line.playerId());
    }

    @Override
    public List<PlayerMatchView> historyOf(UUID playerId) {
        return jdbc.query("""
                SELECT m.id AS match_id, m.opponent, t.name AS tournament_name, e.start_at, e.status AS match_status,
                       c.minutes_played, c.goals, m.score_for, m.score_against
                FROM callup_entries c
                         JOIN matches m ON m.id = c.match_id
                         JOIN sports_events e ON e.id = m.event_id
                         LEFT JOIN tournaments t ON t.id = m.tournament_id
                WHERE c.player_id = ? AND c.status = 'CONFIRMED'
                ORDER BY e.start_at DESC, m.id
                """, (rs, rowNum) -> new PlayerMatchView(rs.getObject("match_id", UUID.class), rs.getString("opponent"),
                rs.getString("tournament_name"), instant(rs.getObject("start_at", OffsetDateTime.class)),
                rs.getString("match_status"), false, rs.getObject("minutes_played", Integer.class),
                rs.getObject("goals", Integer.class), rs.getObject("score_for", Integer.class),
                rs.getObject("score_against", Integer.class)), playerId);
    }
}
