package co.edu.unipamplona.canchatrack.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Agenda y asistencia contra PostgreSQL real: programación con cruces (RN-14), marcas idempotentes, cierre, corrección
 * auditada (RN-15) y porcentaje. Las pruebas comparten la base, así que cada una usa su propia categoría, escenario y
 * entrenador, y horarios lejanos entre sí.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AgendaIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(2200);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    // ---------- utilidades ----------

    private ResultActions send(MockHttpServletRequestBuilder request, Object body, String bearer, String idempotencyKey) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private ResultActions postJson(String path, Object body, String bearer) throws Exception {
        return send(post(API + path), body, bearer, null);
    }

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer, null);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private record Account(UUID id, String token) {
    }

    private Account account(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Personal de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        String token = body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null).andExpect(status().isOk()))
                .get("accessToken").asText();
        return new Account(id, token);
    }

    private UUID newCategory() {
        int year = NEXT_YEAR.getAndAdd(3);
        return jdbc.queryForObject("INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 30) RETURNING id",
                UUID.class, "Agenda " + UUID.randomUUID(), year, year + 1);
    }

    private UUID newVenue(String coordinator) throws Exception {
        return UUID.fromString(body(postJson("/venues", Map.of("name", "Cancha " + UUID.randomUUID()), coordinator)
                .andExpect(status().isCreated())).get("id").asText());
    }

    /** Jugador con asignación vigente en la categoría (la plantilla se deriva de ahí) en el estado indicado. */
    private UUID rosterPlayer(UUID category, UUID coach, String playerStatus) {
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Luis', 'Mora', 'TI', ?, DATE '2014-06-01', ?) RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), playerStatus);
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "agenda-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "AG-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);
        return player;
    }

    private Map<String, Object> eventRequest(UUID category, UUID venue, UUID coach, Instant start, int minutes) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventType", "TRAINING");
        request.put("categoryId", category);
        request.put("venueId", venue);
        request.put("headCoachId", coach);
        request.put("startAt", start.toString());
        request.put("endAt", start.plus(minutes, ChronoUnit.MINUTES).toString());
        return request;
    }

    private Map<String, Object> mark(UUID player, String status) {
        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("clientUuid", UUID.randomUUID());
        mark.put("playerId", player);
        mark.put("status", status);
        return mark;
    }

    /** Programa por la API y mueve el evento al pasado por SQL, para poder tomar asistencia. */
    private UUID startedEvent(String coordinator, UUID category, UUID venue, UUID coach) throws Exception {
        Instant start = Instant.now().plus(40 + (long) (Math.random() * 1000), ChronoUnit.DAYS);
        UUID event = UUID.fromString(body(postJson("/events", eventRequest(category, venue, coach, start, 90), coordinator)
                .andExpect(status().isCreated())).get("id").asText());
        jdbc.update("UPDATE sports_events SET start_at = now() - interval '3 hours', end_at = now() - interval '90 minutes' WHERE id = ?", event);
        return event;
    }

    // ---------- pruebas ----------

    @Test
    void schedulingDetectsVenueCoachAndCategoryOverlapsBeforeSaving() throws Exception {
        Account coordinator = account("COORDINATOR");
        Account coach = account("COACH");
        Account otherCoach = account("COACH");
        UUID category = newCategory();
        UUID otherCategory = newCategory();
        UUID venue = newVenue(coordinator.token());
        UUID otherVenue = newVenue(coordinator.token());
        Instant start = Instant.now().plus(100, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        UUID first = UUID.fromString(body(postJson("/events", eventRequest(category, venue, coach.id(), start, 60), coordinator.token())
                .andExpect(status().isCreated())).get("id").asText());

        // Mismo escenario, otro entrenador y otra categoría: cruce de escenario.
        JsonNode venueClash = body(postJson("/events", eventRequest(otherCategory, venue, otherCoach.id(), start.plus(30, ChronoUnit.MINUTES), 60),
                coordinator.token()).andExpect(status().isConflict()));
        assertThat(venueClash.get("code").asText()).isEqualTo("SCHEDULE_CONFLICT");
        assertThat(venueClash.get("conflicts").get(0).get("dimension").asText()).isEqualTo("VENUE");
        assertThat(venueClash.get("conflicts").get(0).get("eventId").asText()).isEqualTo(first.toString());

        // Mismo entrenador en otro escenario y categoría: cruce de entrenador. Misma categoría, otro todo: cruce de categoría.
        JsonNode coachClash = body(postJson("/events", eventRequest(otherCategory, otherVenue, coach.id(), start, 60), coordinator.token())
                .andExpect(status().isConflict()));
        assertThat(coachClash.get("conflicts").findValuesAsText("dimension")).containsExactly("COACH");
        JsonNode categoryClash = body(postJson("/events", eventRequest(category, otherVenue, otherCoach.id(), start, 60), coordinator.token())
                .andExpect(status().isConflict()));
        assertThat(categoryClash.get("conflicts").findValuesAsText("dimension")).containsExactly("CATEGORY");

        // Justo después del fin no hay cruce; un evento cancelado libera el horario.
        postJson("/events", eventRequest(category, venue, coach.id(), start.plus(60, ChronoUnit.MINUTES), 60), coordinator.token())
                .andExpect(status().isCreated());
        postJson("/events/" + first + "/cancel", Map.of("reason", "Lluvia"), coordinator.token()).andExpect(status().isOk());
        postJson("/events", eventRequest(otherCategory, venue, otherCoach.id(), start, 60), coordinator.token()).andExpect(status().isCreated());
        postJson("/events/" + first + "/cancel", Map.of("reason", "otra vez"), coordinator.token()).andExpect(status().isConflict());

        // Datos inválidos: errores juntos; el entrenador debe tener rol COACH.
        Account evaluator = account("EVALUATOR");
        Map<String, Object> bad = eventRequest(category, venue, evaluator.id(), Instant.now().minus(1, ChronoUnit.DAYS), 60);
        bad.put("eventType", "PARTIDO");
        JsonNode errors = body(postJson("/events", bad, coordinator.token()).andExpect(status().isUnprocessableEntity()));
        assertThat(errors.get("errors").findValuesAsText("field")).contains("eventType", "startAt", "headCoachId");

        // El entrenador solo ve sus eventos (el cancelado también aparece en el listado).
        assertThat(body(getJson("/events?categoryId=" + category, coach.token()).andExpect(status().isOk())).size()).isEqualTo(2);
        assertThat(body(getJson("/events?categoryId=" + category, otherCoach.token()).andExpect(status().isOk())).size()).isZero();
        getJson("/events/" + first, otherCoach.token()).andExpect(status().isNotFound());
        postJson("/events", eventRequest(category, venue, coach.id(), start, 60), coach.token()).andExpect(status().isForbidden());
    }

    @Test
    void attendanceIsIdempotentClosesWithAbsencesAndOnlyChangesWithAJustifiedCorrection() throws Exception {
        Account coordinator = account("COORDINATOR");
        Account coach = account("COACH");
        Account stranger = account("COACH");
        UUID category = newCategory();
        UUID venue = newVenue(coordinator.token());
        UUID ana = rosterPlayer(category, coach.id(), "ACTIVE");
        UUID beto = rosterPlayer(category, coach.id(), "ACTIVE");
        UUID caro = rosterPlayer(category, coach.id(), "ACTIVE");
        UUID suspended = rosterPlayer(category, coach.id(), "SUSPENDED");
        UUID event = startedEvent(coordinator.token(), category, venue, coach.id());
        String path = "/events/" + event + "/attendance";

        // La plantilla solo trae jugadores activos; un entrenador ajeno no ve la sesión.
        JsonNode roster = body(getJson(path, coach.token()).andExpect(status().isOk()));
        assertThat(roster.get("entries").size()).isEqualTo(3);
        getJson(path, stranger.token()).andExpect(status().isNotFound());

        // Lote: dos marcas válidas, una de un jugador fuera de la plantilla y una con estado inválido.
        Map<String, Object> anaMark = mark(ana, "PRESENT");
        Map<String, Object> batch = Map.of("records", List.of(anaMark, mark(beto, "LATE"), mark(suspended, "PRESENT"), mark(caro, "NADA")));
        String key = "sync-" + UUID.randomUUID();
        JsonNode first = body(send(post(API + path), batch, coach.token(), key).andExpect(status().isOk()));
        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(first.get("accepted").asInt()).isEqualTo(2);
        assertThat(first.get("rejected").asInt()).isEqualTo(2);

        // Reintento con la misma clave: mismo resultado, sin duplicar. Otra clave con la misma marca: duplicada.
        JsonNode replay = body(send(post(API + path), batch, coach.token(), key).andExpect(status().isOk()));
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("accepted").asInt()).isEqualTo(2);
        JsonNode dup = body(send(post(API + path), Map.of("records", List.of(anaMark)), coach.token(), "otra-" + UUID.randomUUID())
                .andExpect(status().isOk()));
        assertThat(dup.get("duplicates").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE event_id = ?", Integer.class, event)).isEqualTo(2);

        // Sin clave, 400; entrenador ajeno, 404.
        send(post(API + path), batch, coach.token(), null).andExpect(status().isBadRequest());
        send(post(API + path), batch, stranger.token(), "x-" + UUID.randomUUID()).andExpect(status().isNotFound());

        // Cierre: quien no tiene marca queda ausente; ya no se puede marcar ni cerrar de nuevo.
        JsonNode closed = body(postJson(path + "/close", null, coach.token()).andExpect(status().isOk()));
        assertThat(closed.get("autoAbsent").asInt()).isEqualTo(1);
        assertThat(closed.get("total").asInt()).isEqualTo(3);
        JsonNode blocked = body(send(post(API + path), Map.of("records", List.of(mark(caro, "PRESENT"))), coach.token(), "k-" + UUID.randomUUID())
                .andExpect(status().isConflict()));
        assertThat(blocked.get("code").asText()).isEqualTo("ATTENDANCE_SESSION_CLOSED");
        postJson(path + "/close", null, coach.token()).andExpect(status().isConflict());

        // Corrección: exige justificación y deja rastro; el porcentaje la refleja (excusa aceptada no cuenta).
        JsonNode summaryBefore = body(getJson("/players/" + caro + "/attendance", coordinator.token()).andExpect(status().isOk()));
        assertThat(summaryBefore.get("absent").asInt()).isEqualTo(1);
        assertThat(summaryBefore.get("percentage").decimalValue()).isEqualByComparingTo("0");
        postJson(path + "/corrections", Map.of("playerId", caro, "status", "EXCUSED", "excuseAccepted", true, "reason", " "), coach.token())
                .andExpect(status().isUnprocessableEntity());
        postJson(path + "/corrections", Map.of("playerId", caro, "status", "EXCUSED", "excuseAccepted", true, "reason", "Certificado médico"),
                coach.token()).andExpect(status().isOk());
        JsonNode summaryAfter = body(getJson("/players/" + caro + "/attendance", coordinator.token()).andExpect(status().isOk()));
        assertThat(summaryAfter.get("acceptedExcuses").asInt()).isEqualTo(1);
        assertThat(summaryAfter.hasNonNull("percentage")).isFalse(); // sin sesiones que contar
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_corrections WHERE reason = 'Certificado médico'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action IN ('ATTENDANCE_SESSION_CLOSED', 'ATTENDANCE_CORRECTED')",
                Integer.class, event)).isEqualTo(2);

        // Ana asistió y Beto llegó tarde: ambos suman a su porcentaje.
        assertThat(body(getJson("/players/" + ana + "/attendance", coordinator.token())).get("percentage").decimalValue()).isEqualByComparingTo("100");
        assertThat(body(getJson("/players/" + beto + "/attendance", coordinator.token())).get("late").asInt()).isEqualTo(1);
    }

    @Test
    void attendanceCannotBeTakenBeforeTheSessionStartsOrOnACancelledEvent() throws Exception {
        Account coordinator = account("COORDINATOR");
        Account coach = account("COACH");
        UUID category = newCategory();
        UUID venue = newVenue(coordinator.token());
        UUID player = rosterPlayer(category, coach.id(), "ACTIVE");
        Instant start = Instant.now().plus(200, ChronoUnit.DAYS);
        UUID future = UUID.fromString(body(postJson("/events", eventRequest(category, venue, coach.id(), start, 60), coordinator.token())
                .andExpect(status().isCreated())).get("id").asText());
        Map<String, Object> batch = Map.of("records", List.of(mark(player, "PRESENT")));
        send(post(API + "/events/" + future + "/attendance"), batch, coach.token(), "k-" + UUID.randomUUID()).andExpect(status().isConflict());

        UUID started = startedEvent(coordinator.token(), category, venue, coach.id());
        postJson("/events/" + started + "/cancel", Map.of("reason", "Cancelado"), coordinator.token()).andExpect(status().isOk());
        send(post(API + "/events/" + started + "/attendance"), batch, coach.token(), "k-" + UUID.randomUUID()).andExpect(status().isConflict());
    }
}
