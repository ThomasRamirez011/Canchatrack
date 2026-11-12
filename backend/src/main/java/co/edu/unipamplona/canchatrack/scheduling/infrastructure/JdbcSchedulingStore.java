package co.edu.unipamplona.canchatrack.scheduling.infrastructure;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.scheduling.application.ConflictView;
import co.edu.unipamplona.canchatrack.scheduling.application.DuplicateVenueException;
import co.edu.unipamplona.canchatrack.scheduling.application.EventContext;
import co.edu.unipamplona.canchatrack.scheduling.application.EventView;
import co.edu.unipamplona.canchatrack.scheduling.application.ReferenceNotFoundException;
import co.edu.unipamplona.canchatrack.scheduling.application.ReminderCandidate;
import co.edu.unipamplona.canchatrack.scheduling.application.SchedulingStore;
import co.edu.unipamplona.canchatrack.scheduling.application.VenueInfo;

@Repository
public class JdbcSchedulingStore implements SchedulingStore {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static final RowMapper<VenueInfo> VENUE = (rs, rowNum) -> new VenueInfo(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getString("address"), rs.getString("reference"), rs.getBoolean("active"));

    private static final String EVENT_SELECT = """
            SELECT e.id, e.event_type, e.category_id, e.venue_id, v.name AS venue_name, hc.user_id AS head_coach_id,
                   e.start_at, e.end_at, e.meeting_at, e.meeting_point, e.instructions, e.status, e.cancellation_reason
            FROM sports_events e
                     JOIN venues v ON v.id = e.venue_id
                     LEFT JOIN event_participants hc ON hc.event_id = e.id AND hc.role = 'HEAD_COACH'
            """;

    private static final RowMapper<EventView> EVENT = (rs, rowNum) -> new EventView(rs.getObject("id", UUID.class),
            rs.getString("event_type"), rs.getObject("category_id", UUID.class), rs.getObject("venue_id", UUID.class),
            rs.getString("venue_name"), rs.getObject("head_coach_id", UUID.class), instant(rs.getObject("start_at", OffsetDateTime.class)),
            instant(rs.getObject("end_at", OffsetDateTime.class)), instant(rs.getObject("meeting_at", OffsetDateTime.class)),
            rs.getString("meeting_point"), rs.getString("instructions"), rs.getString("status"), rs.getString("cancellation_reason"));

    private final JdbcTemplate jdbc;

    public JdbcSchedulingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    @Override
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    @Override
    public VenueInfo insertVenue(String name, String address, String reference, UUID createdBy) {
        try {
            return jdbc.queryForObject("INSERT INTO venues (name, address, reference, created_by) VALUES (?, ?, ?, ?) "
                    + "RETURNING id, name, address, reference, active", VENUE, name, address, reference, createdBy);
        } catch (DuplicateKeyException e) {
            throw new DuplicateVenueException();
        }
    }

    @Override
    public List<VenueInfo> findVenues(boolean includeInactive) {
        return jdbc.query("SELECT id, name, address, reference, active FROM venues WHERE active OR ? ORDER BY name",
                VENUE, includeInactive);
    }

    @Override
    public Optional<VenueInfo> findVenue(UUID venueId) {
        return jdbc.query("SELECT id, name, address, reference, active FROM venues WHERE id = ?", VENUE, venueId)
                .stream().findFirst();
    }

    @Override
    public void lockSlots(UUID venueId, UUID coachId, UUID categoryId) {
        // Orden fijo de claves: dos solicitudes que comparten recursos toman los bloqueos en el mismo orden (sin interbloqueo).
        List<String> keys = new ArrayList<>(List.of("venue:" + venueId, "coach:" + coachId, "category:" + categoryId));
        keys.sort(String::compareTo);
        for (String key : keys) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, key);
        }
    }

    @Override
    public List<ConflictView> findConflicts(UUID venueId, UUID coachId, UUID categoryId, Instant startAt, Instant endAt,
                                            UUID excludeEventId) {
        return jdbc.query("""
                SELECT dimension, id, start_at, end_at
                FROM (SELECT 'VENUE' AS dimension, e.id, e.start_at, e.end_at
                      FROM sports_events e
                      WHERE e.venue_id = ? AND e.status <> 'CANCELLED' AND e.start_at < ? AND ? < e.end_at
                      UNION ALL
                      SELECT 'COACH', e.id, e.start_at, e.end_at
                      FROM sports_events e
                               JOIN event_participants p ON p.event_id = e.id AND p.user_id = ? AND p.role IN ('HEAD_COACH', 'ASSISTANT_COACH')
                      WHERE e.status <> 'CANCELLED' AND e.start_at < ? AND ? < e.end_at
                      UNION ALL
                      SELECT 'CATEGORY', e.id, e.start_at, e.end_at
                      FROM sports_events e
                      WHERE e.category_id = ? AND e.status <> 'CANCELLED' AND e.start_at < ? AND ? < e.end_at) c
                WHERE ?::uuid IS NULL OR c.id <> ?::uuid
                ORDER BY start_at, dimension
                """, (rs, rowNum) -> new ConflictView(rs.getString("dimension"), rs.getObject("id", UUID.class),
                rs.getObject("start_at", OffsetDateTime.class).toInstant(), rs.getObject("end_at", OffsetDateTime.class).toInstant()),
                venueId, utc(endAt), utc(startAt), coachId, utc(endAt), utc(startAt), categoryId, utc(endAt), utc(startAt),
                excludeEventId, excludeEventId);
    }

    @Override
    public UUID insertEvent(String eventType, UUID categoryId, UUID venueId, Instant startAt, Instant endAt, Instant meetingAt,
                            String meetingPoint, String instructions, UUID headCoachId, UUID createdBy) {
        try {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at, meeting_at, meeting_point,
                                               instructions, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, UUID.class, eventType, categoryId, venueId, utc(startAt), utc(endAt), utc(meetingAt), meetingPoint,
                    instructions, createdBy);
            jdbc.update("INSERT INTO event_participants (event_id, user_id, role, created_by) VALUES (?, ?, 'HEAD_COACH', ?)",
                    id, headCoachId, createdBy);
            return id;
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
                throw new ReferenceNotFoundException();
            }
            throw e;
        }
    }

    @Override
    public Optional<EventView> findEvent(UUID eventId) {
        return jdbc.query(EVENT_SELECT + " WHERE e.id = ?", EVENT, eventId).stream().findFirst();
    }

    @Override
    public Optional<String> lockStatus(UUID eventId) {
        return jdbc.queryForList("SELECT status FROM sports_events WHERE id = ? FOR UPDATE", String.class, eventId)
                .stream().findFirst();
    }

    @Override
    public void complete(UUID eventId) {
        jdbc.update("UPDATE sports_events SET status = 'COMPLETED', optimistic_version = optimistic_version + 1 WHERE id = ? AND status = 'CONFIRMED'",
                eventId);
    }

    @Override
    public void cancel(UUID eventId, String reason) {
        jdbc.update("UPDATE sports_events SET status = 'CANCELLED', cancellation_reason = ?, optimistic_version = optimistic_version + 1 WHERE id = ?",
                reason, eventId);
    }

    @Override
    public void reschedule(UUID eventId, UUID venueId, UUID headCoachId, Instant startAt, Instant endAt, Instant meetingAt,
                           String meetingPoint, String instructions) {
        jdbc.update("""
                UPDATE sports_events
                SET venue_id = ?, start_at = ?, end_at = ?, meeting_at = ?, meeting_point = ?, instructions = ?,
                    optimistic_version = optimistic_version + 1
                WHERE id = ? AND status = 'CONFIRMED'
                """, venueId, utc(startAt), utc(endAt), utc(meetingAt), meetingPoint, instructions, eventId);
        if (headCoachId != null
                && jdbc.update("UPDATE event_participants SET user_id = ? WHERE event_id = ? AND role = 'HEAD_COACH'", headCoachId, eventId) == 0) {
            // Evento que se creó sin entrenador y ahora lo tiene.
            jdbc.update("INSERT INTO event_participants (event_id, user_id, role) VALUES (?, ?, 'HEAD_COACH')", eventId, headCoachId);
        }
    }

    @Override
    public int revisionOf(UUID eventId) {
        Long revision = jdbc.queryForObject("SELECT optimistic_version FROM sports_events WHERE id = ?", Long.class, eventId);
        return revision == null ? 0 : Math.toIntExact(revision);
    }

    /**
     * Lee el nombre del entrenador de {@code users} (tabla de identity) solo para redactar el aviso; se hace con SQL de solo
     * lectura para no acoplar este módulo a las clases de identity. Un cambio en esa tabla obliga a revisar esta consulta.
     */
    @Override
    public EventContext contextOf(EventView event) {
        List<EventContext> found = jdbc.query("""
                SELECT c.name AS category_name, COALESCE(v.address, v.reference) AS venue_address, u.full_name AS coach_name
                FROM sports_events e
                         JOIN categories c ON c.id = e.category_id
                         JOIN venues v ON v.id = e.venue_id
                         LEFT JOIN users u ON u.id = ?::uuid
                WHERE e.id = ?
                """, (rs, rowNum) -> new EventContext(rs.getString("category_name"), rs.getString("venue_address"),
                rs.getString("coach_name")), event.headCoachId(), event.id());
        return found.isEmpty() ? new EventContext(null, null, null) : found.get(0);
    }

    @Override
    public List<ReminderCandidate> findUpcomingForReminders(Instant from, Instant to) {
        List<Object[]> rows = jdbc.query("""
                SELECT e.id, e.updated_at
                FROM sports_events e
                WHERE e.status = 'CONFIRMED' AND e.event_type IN ('TRAINING', 'EVALUATION')
                  AND e.start_at > ? AND e.start_at <= ?
                ORDER BY e.start_at, e.id
                """, (rs, rowNum) -> new Object[]{rs.getObject("id", UUID.class),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()}, utc(from), utc(to));
        List<ReminderCandidate> candidates = new ArrayList<>();
        for (Object[] row : rows) {
            findEvent((UUID) row[0]).ifPresent(event -> candidates.add(new ReminderCandidate(event, (Instant) row[1])));
        }
        return candidates;
    }

    @Override
    public List<EventView> findEvents(UUID categoryId, Instant from, Instant to, UUID coachId, int limit) {
        return jdbc.query(EVENT_SELECT + """
                 WHERE (?::uuid IS NULL OR e.category_id = ?::uuid)
                   AND (?::timestamptz IS NULL OR e.start_at >= ?::timestamptz)
                   AND (?::timestamptz IS NULL OR e.start_at < ?::timestamptz)
                   AND (?::uuid IS NULL OR hc.user_id = ?::uuid)
                 ORDER BY e.start_at, e.id
                 LIMIT ?
                """, EVENT, categoryId, categoryId, utc(from), utc(from), utc(to), utc(to), coachId, coachId, limit);
    }
}
