package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Cartera por categoría (JSON y CSV) y resumen de temporada contra PostgreSQL real. Cada prueba arma su propia temporada,
 * categorías y jugadores, con fechas fijas en 2030-2032, para que los totales no dependan de otras pruebas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReportsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(3300);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    // ---------- utilidades ----------

    private record Account(UUID id, String token) {
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body, String bearer) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer);
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

    private Account staff(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = user(email, "Personal de prueba", roleCode);
        String token = body(send(post(API + "/auth/login"), Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isOk())).get("accessToken").asText();
        return new Account(id, token);
    }

    private UUID season(String start, String end) {
        return jdbc.queryForObject("INSERT INTO seasons (name, start_at, end_at, status) VALUES (?, ?::timestamptz, ?::timestamptz, 'ACTIVE') RETURNING id",
                UUID.class, "Temporada " + UUID.randomUUID(), start, end);
    }

    private UUID category(String name, int capacity) {
        int year = NEXT_YEAR.getAndAdd(3);
        return jdbc.queryForObject("""
                INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, ?) RETURNING id
                """, UUID.class, name + " " + UUID.randomUUID().toString().substring(0, 6), year, year + 1, capacity);
    }

    private UUID fee(UUID season) {
        return jdbc.queryForObject("""
                INSERT INTO fees (season_id, concept, name, amount, valid_from) VALUES (?, 'MONTHLY', ?, 100000, DATE '2030-01-01')
                RETURNING id
                """, UUID.class, season, "Mensualidad " + UUID.randomUUID());
    }

    /** Jugador asignado a la categoría, con acudiente principal de nombre dado. */
    private UUID player(UUID category, UUID coach, String firstName, String guardianName) {
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES (?, 'Ruiz', 'TI', ?, DATE '2012-03-01', 'ACTIVE') RETURNING id
                """, UUID.class, firstName, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "reports-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "RP-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);
        UUID guardianUser = user("guardian-" + UUID.randomUUID() + "@familia.test", guardianName, "GUARDIAN");
        UUID guardian = jdbc.queryForObject("INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                UUID.class, guardianUser, "cc-" + UUID.randomUUID().toString().substring(0, 20));
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, true)", player, guardian);
        return player;
    }

    private UUID charge(UUID player, UUID fee, String period, double amount, String dueDate) {
        return jdbc.queryForObject("""
                INSERT INTO charges (player_id, fee_id, period, amount, due_date) VALUES (?, ?, ?::date, ?, ?::date) RETURNING id
                """, UUID.class, player, fee, period, amount, dueDate);
    }

    private void pay(UUID player, UUID fee, UUID charge, double amount, UUID recordedBy) {
        jdbc.update("""
                INSERT INTO payments (player_id, fee_id, charge_id, amount, method, paid_at, recorded_by)
                VALUES (?, ?, ?, ?, 'CASH', now(), ?)
                """, player, fee, charge, amount, recordedBy);
    }

    private static List<String> names(JsonNode lines) {
        return StreamSupport.stream(lines.spliterator(), false).map(l -> l.get("playerName").asText()).toList();
    }

    private JsonNode categoryTotals(JsonNode report, UUID category) {
        return StreamSupport.stream(report.get("categories").spliterator(), false)
                .filter(c -> c.has("categoryId") && c.get("categoryId").asText().equals(category.toString()))
                .findFirst().orElseThrow();
    }

    /** Dos categorías con un jugador cada una en una temporada propia: Ana debe 100.000 (vencido) y Luis, 60.000 de 100.000. */
    private record Scene(Account coordinator, UUID season, UUID youngCategory, UUID olderCategory, UUID ana, UUID luis) {
    }

    private Scene scene() throws Exception {
        Account coordinator = staff("COORDINATOR");
        Account coach = staff("COACH");
        UUID season = season("2030-01-01T00:00:00Z", "2030-12-31T00:00:00Z");
        UUID fee = fee(season);
        UUID young = category("Sub-12", 20);
        UUID older = category("Sub-14", 20);
        UUID ana = player(young, coach.id(), "Ana", "Marta López");
        UUID luis = player(older, coach.id(), "Luis", "Pedro Gómez");

        charge(ana, fee, "2030-01-01", 100000, "2030-01-05");                                     // sin pagos
        UUID luisCharge = charge(luis, fee, "2030-01-01", 100000, "2030-01-20");                  // pago parcial
        pay(luis, fee, luisCharge, 40000, coordinator.id());
        UUID settled = charge(ana, fee, "2030-02-01", 100000, "2030-02-05");                      // saldado: no es cartera
        pay(ana, fee, settled, 100000, coordinator.id());
        UUID voided = charge(luis, fee, "2030-02-01", 100000, "2030-02-05");                      // anulado: no es cartera
        jdbc.update("UPDATE charges SET voided_at = now(), void_reason = 'error', voided_by = ? WHERE id = ?", coordinator.id(), voided);
        return new Scene(coordinator, season, young, older, ana, luis);
    }

    // ---------- cartera ----------

    @Test
    void receivablesListOnlyOpenChargesWithDaysOverdueAndTotalsPerCategory() throws Exception {
        Scene s = scene();

        JsonNode report = body(getJson("/reports/receivables?seasonId=" + s.season() + "&asOf=2030-01-15", s.coordinator().token())
                .andExpect(status().isOk()));

        assertThat(report.get("asOf").asText()).isEqualTo("2030-01-15");
        assertThat(report.get("truncated").asBoolean()).isFalse();
        assertThat(names(report.get("lines"))).containsExactlyInAnyOrder("Ana Ruiz", "Luis Ruiz");
        for (JsonNode line : report.get("lines")) {
            if (line.get("playerName").asText().equals("Ana Ruiz")) {
                assertThat(line.get("guardianName").asText()).isEqualTo("Marta López");
                assertThat(line.get("daysOverdue").asInt()).isEqualTo(10);
                assertThat(line.get("status").asText()).isEqualTo("PENDING");
                assertThat(line.get("outstanding").decimalValue()).isEqualByComparingTo("100000");
            } else {
                assertThat(line.get("daysOverdue").asInt()).as("aún no vence").isZero();
                assertThat(line.get("status").asText()).isEqualTo("PARTIAL");
                assertThat(line.get("amount").decimalValue()).isEqualByComparingTo("100000");
                assertThat(line.get("applied").decimalValue()).isEqualByComparingTo("40000");
                assertThat(line.get("outstanding").decimalValue()).isEqualByComparingTo("60000");
            }
        }
        JsonNode young = categoryTotals(report, s.youngCategory());
        assertThat(young.get("players").asInt()).isEqualTo(1);
        assertThat(young.get("outstanding").decimalValue()).isEqualByComparingTo("100000");
        assertThat(young.get("overdue").decimalValue()).isEqualByComparingTo("100000");
        JsonNode older = categoryTotals(report, s.olderCategory());
        assertThat(older.get("charged").decimalValue()).isEqualByComparingTo("100000");
        assertThat(older.get("applied").decimalValue()).isEqualByComparingTo("40000");
        assertThat(older.get("overdue").decimalValue()).as("el saldo de Luis vence el 20").isZero();

        // Más adelante el mismo saldo ya está vencido y los días de mora crecen.
        JsonNode later = body(getJson("/reports/receivables?seasonId=" + s.season() + "&asOf=2030-02-01", s.coordinator().token())
                .andExpect(status().isOk()));
        assertThat(categoryTotals(later, s.olderCategory()).get("overdue").decimalValue()).isEqualByComparingTo("60000");
        for (JsonNode line : later.get("lines")) {
            if (line.get("playerName").asText().equals("Luis Ruiz")) {
                assertThat(line.get("daysOverdue").asInt()).isEqualTo(12);
            }
        }
    }

    @Test
    void receivablesCanBeNarrowedToOneCategoryAndUnknownFiltersAreRejected() throws Exception {
        Scene s = scene();

        JsonNode olderOnly = body(getJson("/reports/receivables?seasonId=" + s.season() + "&categoryId=" + s.olderCategory()
                + "&asOf=2030-01-15", s.coordinator().token()).andExpect(status().isOk()));
        assertThat(names(olderOnly.get("lines"))).containsExactly("Luis Ruiz");
        assertThat(olderOnly.get("categories")).hasSize(1);

        JsonNode problem = body(getJson("/reports/receivables?seasonId=" + UUID.randomUUID() + "&categoryId=" + UUID.randomUUID(),
                s.coordinator().token()).andExpect(status().isUnprocessableEntity()));
        assertThat(StreamSupport.stream(problem.get("errors").spliterator(), false).map(e -> e.get("field").asText()).toList())
                .containsExactlyInAnyOrder("seasonId", "categoryId");
    }

    @Test
    void receivablesAreVisibleToCoordinatorAndCommitteeOnly() throws Exception {
        getJson("/reports/receivables", null).andExpect(status().isUnauthorized());
        getJson("/reports/season-summary?seasonId=" + UUID.randomUUID(), null).andExpect(status().isUnauthorized());
        for (String role : List.of("GUARDIAN", "COACH", "EVALUATOR")) {
            String token = staff(role).token();
            getJson("/reports/receivables", token).andExpect(status().isForbidden());
            getJson("/reports/receivables/export", token).andExpect(status().isForbidden());
            getJson("/reports/season-summary?seasonId=" + UUID.randomUUID(), token).andExpect(status().isForbidden());
        }
        Scene s = scene();
        getJson("/reports/receivables?seasonId=" + s.season(), staff("COMMITTEE").token()).andExpect(status().isOk());
    }

    @Test
    void theCsvExportIsExcelFriendlySafeAndAudited() throws Exception {
        Account coordinator = staff("COORDINATOR");
        Account coach = staff("COACH");
        UUID season = season("2030-01-01T00:00:00Z", "2030-12-31T00:00:00Z");
        UUID fee = fee(season);
        UUID category = category("Sub-16", 20);
        // El nombre del acudiente lo escribe un acudiente: una fórmula con coma y comillas no debe llegar viva al Excel.
        UUID player = player(category, coach.id(), "Eva", "=HYPERLINK(\"http://x\"),mala");
        charge(player, fee, "2030-01-01", 100000, "2030-01-05");

        MockHttpServletResponse response = getJson("/reports/receivables/export?seasonId=" + season + "&asOf=2030-01-15",
                coordinator.token()).andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains("cartera-2030-01-15.csv");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Categoría,Código,Jugador,Acudiente,");
        String[] rows = csv.split("\r\n");
        assertThat(rows).hasSize(2);
        assertThat(rows[1]).contains("Eva Ruiz")
                .contains("\"'=HYPERLINK(\"\"http://x\"\"),mala\"")
                .endsWith(",100000.00,0,100000.00,10,Pendiente");

        Map<String, Object> trace = jdbc.queryForMap("""
                SELECT actor_role, entity_type, after_state ->> 'report' AS report, after_state ->> 'format' AS format,
                       after_state ->> 'rows' AS rows, after_state ->> 'seasonId' AS season_id
                FROM audit_events WHERE action = 'REPORT_EXPORTED' AND actor_id = ?
                """, coordinator.id());
        assertThat(trace).containsEntry("actor_role", "COORDINATOR").containsEntry("entity_type", "report")
                .containsEntry("report", "receivables").containsEntry("format", "csv").containsEntry("rows", "1")
                .containsEntry("season_id", season.toString());
    }

    @Test
    void anExportByTheCommitteeIsAuditedWithItsOwnRole() throws Exception {
        Account committee = staff("COMMITTEE");
        UUID season = season("2030-01-01T00:00:00Z", "2030-12-31T00:00:00Z");

        getJson("/reports/receivables/export?seasonId=" + season, committee.token()).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT actor_role FROM audit_events WHERE action = 'REPORT_EXPORTED' AND actor_id = ?",
                String.class, committee.id())).isEqualTo("COMMITTEE");
        // La consulta JSON, en cambio, no deja rastro de exportación.
        Account other = staff("COORDINATOR");
        getJson("/reports/receivables?seasonId=" + season, other.token()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REPORT_EXPORTED' AND actor_id = ?",
                Long.class, other.id())).isZero();
    }

    // ---------- resumen de temporada ----------

    private UUID venue() {
        return jdbc.queryForObject("INSERT INTO venues (name) VALUES (?) RETURNING id", UUID.class, "Cancha " + UUID.randomUUID());
    }

    private UUID event(UUID category, UUID venue, String type, String status, String start) {
        return jdbc.queryForObject("""
                INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at, status, cancellation_reason)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz + interval '1 hour', ?, CASE WHEN ? = 'CANCELLED' THEN 'lluvia' END)
                RETURNING id
                """, UUID.class, type, category, venue, start, start, status, status);
    }

    private void mark(UUID event, UUID player, String status, boolean excuseAccepted, UUID recordedBy) {
        jdbc.update("""
                INSERT INTO attendance_records (event_id, player_id, status, excuse_accepted, client_uuid, recorded_by)
                VALUES (?, ?, ?, ?, gen_random_uuid(), ?)
                """, event, player, status, excuseAccepted, recordedBy);
    }

    @Test
    void theSeasonSummaryAddsUpRosterAgendaAttendanceMatchesAndBilling() throws Exception {
        Account coordinator = staff("COORDINATOR");
        Account coach = staff("COACH");
        UUID season = season("2031-01-01T00:00:00Z", "2031-12-31T00:00:00Z");
        UUID fee = fee(season);
        UUID category = category("Sub-18", 25);
        UUID venue = venue();
        UUID ana = player(category, coach.id(), "Ana", "Marta López");
        UUID luis = player(category, coach.id(), "Luis", "Pedro Gómez");
        UUID eva = player(category, coach.id(), "Eva", "Rosa Díaz");
        UUID tomas = player(category, coach.id(), "Tomás", "Iván Ruiz");

        // Agenda: un entrenamiento con asistencia cerrada, uno cancelado, dos partidos completados y uno de otro año.
        UUID training = event(category, venue, "TRAINING", "CONFIRMED", "2031-03-02T10:00:00Z");
        event(category, venue, "TRAINING", "CANCELLED", "2031-03-03T10:00:00Z");
        UUID win = event(category, venue, "FRIENDLY_MATCH", "COMPLETED", "2031-03-09T10:00:00Z");
        UUID draw = event(category, venue, "TOURNAMENT_MATCH", "COMPLETED", "2031-03-16T10:00:00Z");
        event(category, venue, "TRAINING", "CONFIRMED", "2032-03-02T10:00:00Z");
        jdbc.update("INSERT INTO matches (event_id, opponent, score_for, score_against) VALUES (?, 'Rival A', 3, 1)", win);
        jdbc.update("INSERT INTO matches (event_id, opponent, score_for, score_against) VALUES (?, 'Rival B', 2, 2)", draw);

        // Asistencia: presente, tarde, excusa aceptada y ausente => (1 + 1) / (4 - 1) = 66,7 %.
        jdbc.update("INSERT INTO attendance_sessions (event_id) VALUES (?)", training);
        mark(training, ana, "PRESENT", false, coach.id());
        mark(training, luis, "LATE", false, coach.id());
        mark(training, eva, "EXCUSED", true, coach.id());
        mark(training, tomas, "ABSENT", false, coach.id());
        jdbc.update("UPDATE attendance_sessions SET closed_at = now(), closed_by = ? WHERE event_id = ?", coach.id(), training);

        // Recaudo: 100.000 por jugador, Ana paga todo y Luis la mitad. El vencimiento es 30 días atrás para que el saldo
        // cuente como vencido cuando se ejecute la prueba, sea cual sea la fecha de hoy.
        String overdue = java.time.LocalDate.now().minusDays(30).toString();
        for (UUID player : List.of(ana, luis, eva, tomas)) {
            UUID charge = charge(player, fee, "2031-01-01", 100000, overdue);
            if (player.equals(ana)) {
                pay(player, fee, charge, 100000, coordinator.id());
            } else if (player.equals(luis)) {
                pay(player, fee, charge, 50000, coordinator.id());
            }
        }

        JsonNode summary = body(getJson("/reports/season-summary?seasonId=" + season, coordinator.token()).andExpect(status().isOk()));

        assertThat(summary.get("season").get("id").asText()).isEqualTo(season.toString());
        assertThat(summary.get("season").get("status").asText()).isEqualTo("ACTIVE");

        JsonNode roster = StreamSupport.stream(summary.get("roster").spliterator(), false)
                .filter(r -> r.get("categoryId").asText().equals(category.toString())).findFirst().orElseThrow();
        assertThat(roster.get("capacity").asInt()).isEqualTo(25);
        assertThat(roster.get("players").asInt()).isEqualTo(4);

        Map<String, Integer> events = new java.util.HashMap<>();
        summary.get("events").forEach(e -> events.put(e.get("eventType").asText() + "/" + e.get("status").asText(), e.get("total").asInt()));
        assertThat(events).containsEntry("TRAINING/CONFIRMED", 1).containsEntry("TRAINING/CANCELLED", 1)
                .containsEntry("FRIENDLY_MATCH/COMPLETED", 1).containsEntry("TOURNAMENT_MATCH/COMPLETED", 1);
        assertThat(events.values().stream().mapToInt(Integer::intValue).sum()).as("el entrenamiento de 2032 queda fuera").isEqualTo(4);

        JsonNode attendance = summary.get("attendance");
        assertThat(attendance.get("sessionsClosed").asInt()).isEqualTo(1);
        assertThat(attendance.get("present").asInt()).isEqualTo(1);
        assertThat(attendance.get("late").asInt()).isEqualTo(1);
        assertThat(attendance.get("excused").asInt()).isEqualTo(1);
        assertThat(attendance.get("acceptedExcuses").asInt()).isEqualTo(1);
        assertThat(attendance.get("absent").asInt()).isEqualTo(1);
        assertThat(attendance.get("percentage").decimalValue()).isEqualByComparingTo("66.7");

        JsonNode matches = summary.get("matches");
        assertThat(matches.get("played").asInt()).isEqualTo(2);
        assertThat(matches.get("wins").asInt()).isEqualTo(1);
        assertThat(matches.get("draws").asInt()).isEqualTo(1);
        assertThat(matches.get("losses").asInt()).isZero();
        assertThat(matches.get("goalsFor").asInt()).isEqualTo(5);
        assertThat(matches.get("goalsAgainst").asInt()).isEqualTo(3);

        JsonNode billing = summary.get("billing");
        assertThat(billing.get("charged").decimalValue()).isEqualByComparingTo("400000");
        assertThat(billing.get("applied").decimalValue()).isEqualByComparingTo("150000");
        assertThat(billing.get("outstanding").decimalValue()).isEqualByComparingTo("250000");
        assertThat(billing.get("overdue").decimalValue()).as("todo venció hace 30 días").isEqualByComparingTo("250000");
    }

    @Test
    void anEmptySeasonSummarisesToZerosAndAnOpenSessionDoesNotCount() throws Exception {
        Account coordinator = staff("COORDINATOR");
        Account coach = staff("COACH");
        UUID season = season("2033-01-01T00:00:00Z", "2033-12-31T00:00:00Z");
        UUID category = category("Sub-20", 10);
        UUID ana = player(category, coach.id(), "Ana", "Marta López");
        UUID open = event(category, venue(), "TRAINING", "CONFIRMED", "2033-03-02T10:00:00Z");
        jdbc.update("INSERT INTO attendance_sessions (event_id) VALUES (?)", open);
        mark(open, ana, "PRESENT", false, coach.id());

        JsonNode summary = body(getJson("/reports/season-summary?seasonId=" + season, coordinator.token()).andExpect(status().isOk()));

        assertThat(summary.get("attendance").get("sessionsClosed").asInt()).as("la sesión sigue abierta").isZero();
        assertThat(summary.get("attendance").has("percentage")).isFalse();
        assertThat(summary.get("matches").get("played").asInt()).isZero();
        assertThat(summary.get("billing").get("charged").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void theSeasonSummaryRequiresAnExistingSeason() throws Exception {
        Account coordinator = staff("COORDINATOR");

        getJson("/reports/season-summary?seasonId=" + UUID.randomUUID(), coordinator.token()).andExpect(status().isNotFound());
        JsonNode problem = body(getJson("/reports/season-summary", coordinator.token()).andExpect(status().isUnprocessableEntity()));
        assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("seasonId");
    }
}
