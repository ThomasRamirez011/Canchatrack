package co.edu.unipamplona.canchatrack.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;
import co.edu.unipamplona.canchatrack.notifications.application.EmailDeliveryException;
import co.edu.unipamplona.canchatrack.notifications.application.EmailSender;
import co.edu.unipamplona.canchatrack.notifications.application.NotificationProcessor;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/**
 * Sesiones recurrentes y calendario filtrable contra PostgreSQL real (RF-013, RF-014): una serie semanal crea una sesión por
 * fecha a la hora local del club, es todo o nada ante cruces, avisa con un solo correo con la invitación de todas las
 * sesiones y se cancela de una vez; el calendario combina los filtros y el entrenador solo ve lo suyo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecurringSessionsIT.SenderConfig.class})
@ActiveProfiles("test")
class RecurringSessionsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private NotificationProcessor processor;
    @Autowired
    private CapturingSender sender;

    // ---------- utilidades ----------

    private ResultActions postJson(String path, Object body, String bearer) throws Exception {
        var request = post(API + path);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions getJson(String path, String bearer) throws Exception {
        var request = get(API + path);
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private UUID user(String email, String fullName, String roleCode) {
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, fullName, encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return id;
    }

    private String login(String email) throws Exception {
        return body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null).andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    /** Categoría con un jugador, su acudiente, un entrenador y un escenario propios (cada prueba usa los suyos). */
    private record Club(String coordinatorToken, String coachToken, String guardianEmail, String coachEmail, UUID category,
                        UUID venue, UUID coach) {
    }

    private static int nextYear = 6000;

    private static synchronized int takeYears() {
        nextYear += 3;
        return nextYear;
    }

    private Club club() throws Exception {
        int year = takeYears();
        UUID category = jdbc.queryForObject("INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 30) RETURNING id",
                UUID.class, "Series " + UUID.randomUUID(), year, year + 1);
        UUID venue = jdbc.queryForObject("INSERT INTO venues (name, address) VALUES (?, 'Calle 5 # 3-20') RETURNING id", UUID.class,
                "Cancha " + UUID.randomUUID());
        String coordinatorEmail = "coord-" + UUID.randomUUID() + "@club.test";
        user(coordinatorEmail, "Coordinación", "COORDINATOR");
        String coachEmail = "coach-" + UUID.randomUUID() + "@club.test";
        UUID coach = user(coachEmail, "Carlos Ruiz", "COACH");
        String guardianEmail = "madre-" + UUID.randomUUID() + "@familia.test";
        UUID guardianUser = user(guardianEmail, "Madre de prueba", "GUARDIAN");
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Ana', 'Jugadorcito', 'TI', ?, DATE '2014-06-01', 'ACTIVE') RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "series-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "SR-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);
        UUID guardian = jdbc.queryForObject("INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                UUID.class, guardianUser, "cc-" + UUID.randomUUID().toString().substring(0, 20));
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, true)", player, guardian);
        return new Club(login(coordinatorEmail), login(coachEmail), guardianEmail, coachEmail, category, venue, coach);
    }

    /** El lunes que cae a partir de dentro de {@code daysAhead} días en la zona del club. */
    private static LocalDate mondayAfter(int daysAhead) {
        return LocalDate.now(BOGOTA).plusDays(daysAhead).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private static Instant at(LocalDate day, String time) {
        return ZonedDateTime.of(day, LocalTime.parse(time), BOGOTA).toInstant();
    }

    /** Lunes y miércoles, de 17:00 a 19:00, desde {@code first} hasta nueve días después: cuatro sesiones. */
    private Map<String, Object> seriesRequest(Club club, LocalDate first) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventType", "TRAINING");
        request.put("categoryId", club.category());
        request.put("venueId", club.venue());
        request.put("headCoachId", club.coach());
        request.put("weekdays", List.of("MONDAY", "WEDNESDAY"));
        request.put("startDate", first.toString());
        request.put("endDate", first.plusDays(9).toString());
        request.put("startTime", "17:00");
        request.put("endTime", "19:00");
        request.put("meetingPoint", "Puerta principal");
        request.put("instructions", "Traer uniforme");
        return request;
    }

    private JsonNode createSeries(Club club, LocalDate first) throws Exception {
        return body(postJson("/event-series", seriesRequest(club, first), club.coordinatorToken()).andExpect(status().isCreated()));
    }

    private static List<UUID> eventIds(JsonNode series) {
        List<UUID> ids = new ArrayList<>();
        series.get("events").forEach(e -> ids.add(UUID.fromString(e.get("id").asText())));
        return ids;
    }

    private List<Map<String, Object>> notices(UUID entity, String type) {
        return jdbc.queryForList("""
                SELECT recipient_email, status, subject, payload::text AS payload
                FROM notifications WHERE related_entity_id = ? AND notification_type = ? ORDER BY recipient_email
                """, entity, type);
    }

    private List<String> recipients(UUID entity, String type) {
        return notices(entity, type).stream().map(n -> (String) n.get("recipient_email")).sorted().toList();
    }

    private static List<String> sorted(String... emails) {
        return Arrays.stream(emails).sorted().toList();
    }

    private static long vevents(String ics) {
        return ics.split("BEGIN:VEVENT", -1).length - 1;
    }

    private JsonNode events(String query, String bearer) throws Exception {
        return body(getJson("/events?" + query, bearer).andExpect(status().isOk()));
    }

    // ---------- pruebas ----------

    @Test
    void aWeeklySeriesCreatesOneEventPerMatchingDateAtTheClubLocalTime() throws Exception {
        Club club = club();
        LocalDate monday = mondayAfter(60);

        JsonNode series = createSeries(club, monday);

        assertThat(series.get("occurrenceCount").asInt()).isEqualTo(4);
        assertThat(series.get("eventType").asText()).isEqualTo("TRAINING");
        assertThat(series.get("timeZone").asText()).isEqualTo("America/Bogota");
        assertThat(series.get("startTime").asText()).startsWith("17:00");
        List<String> days = new ArrayList<>();
        series.get("weekdays").forEach(n -> days.add(n.asText()));
        assertThat(days).containsExactly("MONDAY", "WEDNESDAY");
        List<Instant> starts = new ArrayList<>();
        series.get("events").forEach(e -> starts.add(Instant.parse(e.get("startAt").asText())));
        assertThat(starts).containsExactly(at(monday, "17:00"), at(monday.plusDays(2), "17:00"), at(monday.plusDays(7), "17:00"),
                at(monday.plusDays(9), "17:00"));
        assertThat(Instant.parse(series.get("events").get(0).get("endAt").asText())).isEqualTo(at(monday, "19:00"));
        assertThat(series.get("events").get(0).get("headCoachId").asText()).isEqualTo(club.coach().toString());
        assertThat(series.get("events").get(0).get("meetingPoint").asText()).isEqualTo("Puerta principal");

        UUID seriesId = UUID.fromString(series.get("id").asText());
        assertThat(jdbc.queryForList("SELECT series_position FROM sports_events WHERE series_id = ? ORDER BY start_at", Integer.class, seriesId))
                .containsExactly(1, 2, 3, 4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_participants p JOIN sports_events e ON e.id = p.event_id "
                + "WHERE e.series_id = ? AND p.role = 'HEAD_COACH' AND p.user_id = ?", Integer.class, seriesId, club.coach())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'EVENT_SERIES_SCHEDULED' AND entity_id = ?",
                Integer.class, seriesId)).isEqualTo(1);

        JsonNode again = body(getJson("/event-series/" + seriesId, club.coordinatorToken()).andExpect(status().isOk()));
        assertThat(eventIds(again)).isEqualTo(eventIds(series));
    }

    @Test
    void aSeriesIsOneNoticeWithOneInvitationEventPerSession() throws Exception {
        Club club = club();
        JsonNode series = createSeries(club, mondayAfter(61));
        UUID seriesId = UUID.fromString(series.get("id").asText());

        assertThat(recipients(seriesId, "EVENT_SERIES_SCHEDULED")).isEqualTo(sorted(club.guardianEmail(), club.coachEmail()));
        for (UUID event : eventIds(series)) {
            assertThat(notices(event, "EVENT_SCHEDULED")).isEmpty();
        }
        String payload = (String) notices(seriesId, "EVENT_SERIES_SCHEDULED").get(0).get("payload");
        assertThat(payload).contains("Carlos Ruiz").contains("Calle 5 # 3-20").contains("Traer uniforme");
        assertThat(payload.contains("Jugadorcito")).isFalse();

        for (int i = 0; i < 200 && processor.processDue() > 0; i++) {
            // vaciar la cola
        }
        OutboundEmail email = sender.firstTo(club.guardianEmail(), "Serie de entrenamientos programada");
        assertThat(email.body()).contains("4 entrenamientos", "lun ", "mié ", "Puerta principal");
        assertThat(email.calendar().method()).isEqualTo("REQUEST");
        assertThat(vevents(email.calendar().content())).isEqualTo(4);
        for (UUID event : eventIds(series)) {
            assertThat(email.calendar().content()).contains("UID:" + event + "@canchatrack");
        }
    }

    @Test
    void aConflictOnAnyDateRejectsTheWholeSeriesAndCreatesNothing() throws Exception {
        Club club = club();
        LocalDate monday = mondayAfter(62);
        // Otro evento del mismo escenario y categoría que pisa la tercera sesión (lunes de la semana siguiente).
        Instant clashStart = at(monday.plusDays(7), "17:30");
        UUID other = jdbc.queryForObject("""
                INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at)
                VALUES ('TRAINING', ?, ?, ?, ?) RETURNING id
                """, UUID.class, club.category(), club.venue(), Timestamp.from(clashStart), Timestamp.from(clashStart.plusSeconds(3600)));

        JsonNode problem = body(postJson("/event-series", seriesRequest(club, monday), club.coordinatorToken()).andExpect(status().isConflict()));

        assertThat(problem.get("code").asText()).isEqualTo("SCHEDULE_CONFLICT");
        assertThat(problem.get("conflicts").findValuesAsText("dimension")).containsExactlyInAnyOrder("VENUE", "CATEGORY");
        assertThat(problem.get("conflicts").findValuesAsText("eventId")).containsOnly(other.toString());
        assertThat(problem.get("conflicts").findValuesAsText("occurrenceStartAt")).containsOnly(at(monday.plusDays(7), "17:00").toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sports_events WHERE category_id = ?", Integer.class, club.category())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_series WHERE category_id = ?", Integer.class, club.category())).isZero();
    }

    @Test
    void theSeriesCanRepeatOverASlotFreedByACancelledEvent() throws Exception {
        Club club = club();
        LocalDate monday = mondayAfter(63);
        Instant start = at(monday, "17:00");
        jdbc.update("""
                INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at, status, cancellation_reason)
                VALUES ('TRAINING', ?, ?, ?, ?, 'CANCELLED', 'Lluvia')
                """, club.category(), club.venue(), Timestamp.from(start), Timestamp.from(start.plusSeconds(7200)));

        createSeries(club, monday);
    }

    @Test
    void anInvalidSeriesReportsEveryErrorTogether() throws Exception {
        Club club = club();
        UUID notACoach = user("eval-" + UUID.randomUUID() + "@club.test", "Evaluadora", "EVALUATOR");
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("eventType", "PARTIDO");
        bad.put("categoryId", club.category());
        bad.put("venueId", UUID.randomUUID());
        bad.put("headCoachId", notACoach);
        bad.put("weekdays", List.of("FUNDAY"));
        bad.put("startDate", LocalDate.now(BOGOTA).minusDays(1).toString());
        bad.put("startTime", "19:00");
        bad.put("endTime", "17:00");

        JsonNode problem = body(postJson("/event-series", bad, club.coordinatorToken()).andExpect(status().isUnprocessableEntity()));

        assertThat(problem.get("errors").findValuesAsText("field")).contains("eventType", "weekdays", "startDate", "endDate",
                "endTime", "venueId", "headCoachId");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_series WHERE category_id = ?", Integer.class, club.category())).isZero();
    }

    @Test
    void aSeriesWithTooManySessionsOrNoMatchingDateIsRejected() throws Exception {
        Club club = club();
        LocalDate monday = mondayAfter(64);
        Map<String, Object> everyDay = seriesRequest(club, monday);
        everyDay.put("weekdays", List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"));
        everyDay.put("endDate", monday.plusDays(90).toString());
        JsonNode tooMany = body(postJson("/event-series", everyDay, club.coordinatorToken()).andExpect(status().isUnprocessableEntity()));
        assertThat(tooMany.get("errors").findValuesAsText("field")).containsExactly("endDate");

        Map<String, Object> none = seriesRequest(club, monday);
        none.put("weekdays", List.of("SUNDAY"));
        none.put("endDate", monday.plusDays(3).toString());
        JsonNode noDates = body(postJson("/event-series", none, club.coordinatorToken()).andExpect(status().isUnprocessableEntity()));
        assertThat(noDates.get("errors").findValuesAsText("field")).containsExactly("weekdays");
    }

    @Test
    void cancellingASeriesCancelsTheFutureConfirmedSessionsWithOneNotice() throws Exception {
        Club club = club();
        JsonNode series = createSeries(club, mondayAfter(65));
        UUID seriesId = UUID.fromString(series.get("id").asText());
        List<UUID> ids = eventIds(series);

        // Una sesión ya cancelada antes no se vuelve a tocar ni a avisar.
        postJson("/events/" + ids.get(0) + "/cancel", Map.of("reason", "Feriado"), club.coordinatorToken()).andExpect(status().isOk());

        postJson("/event-series/" + seriesId + "/cancel", Map.of(), club.coordinatorToken()).andExpect(status().isUnprocessableEntity());
        JsonNode cancelled = body(postJson("/event-series/" + seriesId + "/cancel", Map.of("reason", "Cancha cerrada por obras"),
                club.coordinatorToken()).andExpect(status().isOk()));

        assertThat(cancelled.get("events").findValuesAsText("status")).containsOnly("CANCELLED");
        assertThat(jdbc.queryForList("SELECT cancellation_reason FROM sports_events WHERE series_id = ? ORDER BY series_position", String.class, seriesId))
                .containsExactly("Feriado", "Cancha cerrada por obras", "Cancha cerrada por obras", "Cancha cerrada por obras");
        assertThat(recipients(seriesId, "EVENT_SERIES_CANCELLED")).isEqualTo(sorted(club.guardianEmail(), club.coachEmail()));
        String payload = (String) notices(seriesId, "EVENT_SERIES_CANCELLED").get(0).get("payload");
        assertThat(payload).contains("Cancha cerrada por obras").contains("\"count\": \"3\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE notification_type = 'EVENT_CANCELLED' "
                + "AND related_entity_id IN (SELECT id FROM sports_events WHERE series_id = ?)", Integer.class, seriesId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'EVENT_CANCELLED' "
                + "AND entity_id IN (SELECT id FROM sports_events WHERE series_id = ?)", Integer.class, seriesId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'EVENT_SERIES_CANCELLED' AND entity_id = ?",
                Integer.class, seriesId)).isEqualTo(1);

        for (int i = 0; i < 200 && processor.processDue() > 0; i++) {
            // vaciar la cola
        }
        OutboundEmail email = sender.firstTo(club.guardianEmail(), "CANCELADO: serie de entrenamientos");
        assertThat(email.calendar().method()).isEqualTo("CANCEL");
        assertThat(vevents(email.calendar().content())).isEqualTo(3);
        assertThat(email.calendar().content().contains(ids.get(0).toString())).isFalse();

        postJson("/event-series/" + seriesId + "/cancel", Map.of("reason", "otra vez"), club.coordinatorToken()).andExpect(status().isConflict());
        postJson("/event-series/" + UUID.randomUUID() + "/cancel", Map.of("reason", "x"), club.coordinatorToken()).andExpect(status().isNotFound());
    }

    @Test
    void aSessionOfASeriesStillWorksOnItsOwn() throws Exception {
        Club club = club();
        JsonNode series = createSeries(club, mondayAfter(66));
        UUID second = eventIds(series).get(1);
        LocalDate day = LocalDate.parse(series.get("startDate").asText()).plusDays(2);
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("startAt", at(day, "18:00").toString());
        move.put("endAt", at(day, "20:00").toString());
        move.put("reason", "Cancha ocupada");

        postJson("/events/" + second + "/reschedule", move, club.coordinatorToken()).andExpect(status().isOk());

        assertThat(recipients(second, "EVENT_RESCHEDULED")).isEqualTo(sorted(club.guardianEmail(), club.coachEmail()));
        assertThat(jdbc.queryForObject("SELECT series_id FROM sports_events WHERE id = ?", UUID.class, second))
                .isEqualTo(UUID.fromString(series.get("id").asText()));
    }

    @Test
    void theCalendarCombinesFiltersAndTheCoachSeesOnlyHisOwn() throws Exception {
        Club first = club();
        Club second = club();
        LocalDate monday = mondayAfter(70);
        JsonNode seriesA = createSeries(first, monday);
        JsonNode seriesB = createSeries(second, monday);
        String idA = seriesA.get("id").asText();
        String idB = seriesB.get("id").asText();

        String coordinator = first.coordinatorToken();
        assertThat(events("seriesId=" + idA, coordinator)).hasSize(4);
        assertThat(events("venueId=" + first.venue(), coordinator)).hasSize(4);
        assertThat(events("categoryId=" + second.category(), coordinator)).hasSize(4);
        assertThat(events("headCoachId=" + second.coach() + "&venueId=" + second.venue(), coordinator)).hasSize(4);
        assertThat(events("headCoachId=" + second.coach() + "&venueId=" + first.venue(), coordinator)).isEmpty();
        assertThat(events("venueId=" + first.venue() + "&eventType=training", coordinator)).hasSize(4);
        assertThat(events("venueId=" + first.venue() + "&eventType=EVALUATION", coordinator)).isEmpty();
        assertThat(events("venueId=" + first.venue() + "&status=CANCELLED", coordinator)).isEmpty();
        assertThat(events("venueId=" + first.venue() + "&status=CONFIRMED", coordinator)).hasSize(4);

        // Un día del calendario: el rango incluye el inicio y excluye el fin.
        String dayRange = "&from=" + at(monday, "00:00") + "&to=" + at(monday.plusDays(1), "00:00");
        JsonNode oneDay = events("venueId=" + first.venue() + dayRange, coordinator);
        assertThat(oneDay).hasSize(1);
        assertThat(Instant.parse(oneDay.get(0).get("startAt").asText())).isEqualTo(at(monday, "17:00"));
        assertThat(events("venueId=" + first.venue() + "&from=" + at(monday, "17:00") + "&to=" + at(monday, "17:01"), coordinator)).hasSize(1);
        assertThat(events("venueId=" + first.venue() + "&from=" + at(monday, "17:01") + "&to=" + at(monday.plusDays(1), "00:00"), coordinator)).isEmpty();

        // Datos inválidos: errores juntos.
        JsonNode bad = body(getJson("/events?eventType=PARTIDO&status=HECHO&from=" + at(monday, "10:00") + "&to=" + at(monday, "09:00"), coordinator)
                .andExpect(status().isUnprocessableEntity()));
        assertThat(bad.get("errors").findValuesAsText("field")).containsExactly("eventType", "status", "to");

        // El entrenador solo ve lo suyo, pida lo que pida.
        assertThat(events("venueId=" + first.venue(), first.coachToken())).hasSize(4);
        assertThat(events("seriesId=" + idB, first.coachToken())).isEmpty();
        assertThat(events("headCoachId=" + second.coach(), first.coachToken())).isEmpty();
        assertThat(events("headCoachId=" + first.coach() + "&seriesId=" + idA, first.coachToken())).hasSize(4);
        getJson("/event-series/" + idA, first.coachToken()).andExpect(status().isOk());
        getJson("/event-series/" + idB, first.coachToken()).andExpect(status().isNotFound());
        getJson("/event-series/" + idB, coordinator).andExpect(status().isOk());
        getJson("/event-series/" + UUID.randomUUID(), coordinator).andExpect(status().isNotFound());
    }

    @Test
    void onlyTheCoordinatorSchedulesAndCancelsSeries() throws Exception {
        Club club = club();
        JsonNode series = createSeries(club, mondayAfter(72));
        String seriesId = series.get("id").asText();
        String guardianToken = login(club.guardianEmail());

        postJson("/event-series", seriesRequest(club, mondayAfter(80)), club.coachToken()).andExpect(status().isForbidden());
        postJson("/event-series", seriesRequest(club, mondayAfter(80)), guardianToken).andExpect(status().isForbidden());
        postJson("/event-series", seriesRequest(club, mondayAfter(80)), null).andExpect(status().isUnauthorized());
        postJson("/event-series/" + seriesId + "/cancel", Map.of("reason", "x"), club.coachToken()).andExpect(status().isForbidden());
        getJson("/event-series/" + seriesId, guardianToken).andExpect(status().isForbidden());
        getJson("/events?venueId=" + club.venue(), guardianToken).andExpect(status().isForbidden());
    }

    // ---------- proveedor de correo de captura ----------

    @TestConfiguration(proxyBeanMethods = false)
    static class SenderConfig {

        @Bean
        @Primary
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    static final class CapturingSender implements EmailSender {

        private final List<OutboundEmail> delivered = new CopyOnWriteArrayList<>();

        @Override
        public String send(OutboundEmail email) throws EmailDeliveryException {
            delivered.add(email);
            return "capture-" + UUID.randomUUID();
        }

        OutboundEmail firstTo(String address, String subjectStart) {
            return delivered.stream().filter(e -> e.to().equals(address) && e.subject().startsWith(subjectStart)).findFirst()
                    .orElseThrow(() -> new AssertionError("no se entregó '" + subjectStart + "' a " + address));
        }
    }
}
