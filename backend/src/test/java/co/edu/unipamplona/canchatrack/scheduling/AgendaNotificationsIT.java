package co.edu.unipamplona.canchatrack.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import co.edu.unipamplona.canchatrack.scheduling.application.EventReminderService;

/**
 * Avisos de agenda contra PostgreSQL real (RF-016, RF-018, RF-019, RF-020): al programar y cancelar se encolan para los
 * acudientes de la categoría y el entrenador; los recordatorios son idempotentes y respetan cuándo se programó el evento;
 * el correo sale con su invitación de calendario y sin nombres de jugadores.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AgendaNotificationsIT.SenderConfig.class})
@ActiveProfiles("test")
class AgendaNotificationsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(5000);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private EventReminderService reminders;
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

    /** Categoría con entrenador, escenario y dos jugadores: uno con su acudiente y otro con el mismo acudiente más otro. */
    private record Club(String coordinatorToken, UUID category, UUID venue, UUID coach, String coachEmail,
                        String guardianEmail, String secondGuardianEmail) {
    }

    private Club club() throws Exception {
        int year = NEXT_YEAR.getAndAdd(3);
        UUID category = jdbc.queryForObject("INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 30) RETURNING id",
                UUID.class, "Avisos " + UUID.randomUUID(), year, year + 1);
        UUID venue = jdbc.queryForObject("INSERT INTO venues (name, address) VALUES (?, 'Calle 5 # 3-20') RETURNING id", UUID.class,
                "Cancha " + UUID.randomUUID());
        String coordinatorEmail = "coord-" + UUID.randomUUID() + "@club.test";
        user(coordinatorEmail, "Coordinación", "COORDINATOR");
        String coachEmail = "coach-" + UUID.randomUUID() + "@club.test";
        UUID coach = user(coachEmail, "Carlos Ruiz", "COACH");
        String guardianEmail = "madre-" + UUID.randomUUID() + "@familia.test";
        String secondGuardianEmail = "padre-" + UUID.randomUUID() + "@familia.test";
        UUID guardianUser = user(guardianEmail, "Madre de prueba", "GUARDIAN");
        UUID secondGuardianUser = user(secondGuardianEmail, "Padre de prueba", "GUARDIAN");
        UUID p1 = rosterPlayer(category, coach, "Ana");
        UUID p2 = rosterPlayer(category, coach, "Luis");
        link(p1, guardianUser, true);
        link(p2, guardianUser, true); // la misma acudiente de dos hijos: recibe un solo correo
        link(p2, secondGuardianUser, false);
        return new Club(login(coordinatorEmail), category, venue, coach, coachEmail, guardianEmail, secondGuardianEmail);
    }

    private UUID rosterPlayer(UUID category, UUID coach, String firstName) {
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES (?, 'Jugadorcito', 'TI', ?, DATE '2014-06-01', 'ACTIVE') RETURNING id
                """, UUID.class, firstName, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "agenda-notices-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "AN-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);
        return player;
    }

    private void link(UUID player, UUID guardianUser, boolean primary) {
        UUID guardian = jdbc.query("SELECT id FROM guardians WHERE user_id = ?", (rs, i) -> rs.getObject("id", UUID.class), guardianUser)
                .stream().findFirst().orElseGet(() -> jdbc.queryForObject(
                        "INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                        UUID.class, guardianUser, "cc-" + UUID.randomUUID().toString().substring(0, 20)));
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, ?)", player, guardian, primary);
    }

    private UUID schedule(Club club, Instant start) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventType", "TRAINING");
        request.put("categoryId", club.category());
        request.put("venueId", club.venue());
        request.put("headCoachId", club.coach());
        request.put("startAt", start.toString());
        request.put("endAt", start.plus(90, ChronoUnit.MINUTES).toString());
        request.put("instructions", "Traer uniforme");
        return UUID.fromString(body(postJson("/events", request, club.coordinatorToken()).andExpect(status().isCreated())).get("id").asText());
    }

    /** Evento insertado como si se hubiera programado hace una semana: la API siempre lo crea "ahora". */
    private UUID scheduledLongAgo(Club club, Instant start) {
        return jdbc.queryForObject("""
                INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at, created_at)
                VALUES ('TRAINING', ?, ?, ?, ?, now() - interval '7 days') RETURNING id
                """, UUID.class, club.category(), club.venue(), Timestamp.from(start), Timestamp.from(start.plus(90, ChronoUnit.MINUTES)));
    }

    private List<Map<String, Object>> notices(UUID event, String type) {
        return jdbc.queryForList("""
                SELECT recipient_email, status, subject, payload::text AS payload
                FROM notifications WHERE related_entity_id = ? AND notification_type = ? ORDER BY recipient_email
                """, event, type);
    }

    private List<String> recipients(UUID event, String type) {
        return notices(event, type).stream().map(n -> (String) n.get("recipient_email")).sorted().toList();
    }

    private static List<String> sorted(String... emails) {
        return Arrays.stream(emails).sorted().toList();
    }

    // ---------- pruebas ----------

    @Test
    void schedulingQueuesOneNoticePerGuardianAndTheCoach() throws Exception {
        Club club = club();

        UUID event = schedule(club, Instant.now().plus(40, ChronoUnit.DAYS));

        assertThat(recipients(event, "EVENT_SCHEDULED"))
                .isEqualTo(sorted(club.guardianEmail(), club.secondGuardianEmail(), club.coachEmail()));
        Map<String, Object> notice = notices(event, "EVENT_SCHEDULED").get(0);
        assertThat(notice.get("status")).isEqualTo("PENDING");
        assertThat((String) notice.get("subject")).startsWith("Entrenamiento programado");
        String payload = (String) notice.get("payload");
        assertThat(payload).contains("Carlos Ruiz").contains("Calle 5 # 3-20").contains("Traer uniforme");
        assertThat(payload.contains("Ana")).isFalse();
        assertThat(payload.contains("Jugadorcito")).isFalse();
    }

    @Test
    void cancellingQueuesACancellationWithTheReason() throws Exception {
        Club club = club();
        UUID event = schedule(club, Instant.now().plus(41, ChronoUnit.DAYS));

        postJson("/events/" + event + "/cancel", Map.of("reason", "Lluvia fuerte"), club.coordinatorToken()).andExpect(status().isOk());

        assertThat(recipients(event, "EVENT_CANCELLED"))
                .isEqualTo(sorted(club.guardianEmail(), club.secondGuardianEmail(), club.coachEmail()));
        assertThat((String) notices(event, "EVENT_CANCELLED").get(0).get("payload")).contains("Lluvia fuerte");
        assertThat(notices(event, "EVENT_SCHEDULED")).hasSize(3);
    }

    @Test
    void remindersAreSentOncePerStageEvenIfTheProcessRunsManyTimes() throws Exception {
        Club club = club();
        UUID within24 = scheduledLongAgo(club, Instant.now().plus(20, ChronoUnit.HOURS));
        UUID within2 = scheduledLongAgo(club, Instant.now().plus(90, ChronoUnit.MINUTES));
        UUID farAway = scheduledLongAgo(club, Instant.now().plus(10, ChronoUnit.DAYS));

        reminders.sendDue();
        reminders.sendDue();

        assertThat(recipients(within24, "EVENT_REMINDER"))
                .isEqualTo(sorted(club.guardianEmail(), club.secondGuardianEmail(), club.coachEmail()));
        assertThat(recipients(within2, "EVENT_REMINDER"))
                .isEqualTo(sorted(club.guardianEmail(), club.secondGuardianEmail(), club.coachEmail()));
        assertThat(notices(farAway, "EVENT_REMINDER")).isEmpty();

        // Al entrar en el tramo de 2 horas el evento de 20 horas recibe el segundo recordatorio, no uno repetido.
        jdbc.update("UPDATE sports_events SET start_at = ?, end_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plus(100, ChronoUnit.MINUTES)),
                Timestamp.from(Instant.now().plus(190, ChronoUnit.MINUTES)), within24);
        reminders.sendDue();
        reminders.sendDue();
        assertThat(notices(within24, "EVENT_REMINDER")).hasSize(6);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT dedup_key) FROM notifications WHERE related_entity_id = ? AND notification_type = 'EVENT_REMINDER'",
                Integer.class, within24)).isEqualTo(6);
    }

    @Test
    void anEventScheduledInsideTheStageDoesNotGetItsReminder() throws Exception {
        Club club = club();
        UUID event = schedule(club, Instant.now().plus(20, ChronoUnit.HOURS)); // programado ahora, para dentro de 20 horas

        reminders.sendDue();

        assertThat(notices(event, "EVENT_SCHEDULED")).hasSize(3);
        assertThat(notices(event, "EVENT_REMINDER")).isEmpty();
    }

    @Test
    void cancelledEventsGetNoReminder() throws Exception {
        Club club = club();
        UUID event = scheduledLongAgo(club, Instant.now().plus(20, ChronoUnit.HOURS));
        jdbc.update("UPDATE sports_events SET status = 'CANCELLED', cancellation_reason = 'Lluvia' WHERE id = ?", event);

        reminders.sendDue();

        assertThat(notices(event, "EVENT_REMINDER")).isEmpty();
    }

    @Test
    void theDeliveredEmailCarriesTheCalendarInviteOnlyWhenItShould() throws Exception {
        Club club = club();
        UUID scheduled = schedule(club, Instant.now().plus(42, ChronoUnit.DAYS));
        UUID reminded = scheduledLongAgo(club, Instant.now().plus(20, ChronoUnit.HOURS));
        reminders.sendDue();

        for (int i = 0; i < 20 && processor.processDue() > 0; i++) {
            // vaciar la cola
        }

        OutboundEmail invite = sender.firstTo(club.guardianEmail(), "Entrenamiento programado");
        assertThat(invite.calendar().method()).isEqualTo("REQUEST");
        assertThat(invite.calendar().content()).contains("UID:" + scheduled);
        assertThat(invite.body().contains("Jugadorcito")).isFalse();
        OutboundEmail reminder = sender.firstTo(club.guardianEmail(), "Recordatorio");
        assertThat(reminder.calendar() == null).isTrue();
        assertThat(reminder.body()).contains("Faltan");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE related_entity_id IN (?, ?) AND status = 'SENT'",
                Integer.class, scheduled, reminded)).isEqualTo(6);
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
