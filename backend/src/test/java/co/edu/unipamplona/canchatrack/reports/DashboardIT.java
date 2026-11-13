package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Tablero del club contra PostgreSQL real. Arma una categoría propia (años de nacimiento lejanos, para no chocar con las
 * de otras pruebas) y verifica solo esa categoría, porque el tablero es global.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DashboardIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final int BIRTH_YEAR = 7200;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    private UUID user(String email, String fullName, String roleCode) {
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, fullName, encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return id;
    }

    private String token(String roleCode) throws Exception {
        String email = "dash-" + UUID.randomUUID() + "@club.test";
        user(email, "Personal " + roleCode, roleCode);
        String body = mvc.perform(post(API + "/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    private JsonNode overview(String bearer) throws Exception {
        String body = mvc.perform(get(API + "/dashboard/club").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(n -> n.has(field) && value.equals(n.get(field).asText())).findFirst().orElseThrow();
    }

    @Test
    void showsTeamPlayerOverdueChargeAndNextMatch() throws Exception {
        UUID coach = user("coach-" + UUID.randomUUID() + "@club.test", "Entrenador Prueba", "COACH");
        UUID coordinator = user("coord-" + UUID.randomUUID() + "@club.test", "Coordinador Prueba", "COORDINATOR");
        UUID season = jdbc.queryForObject("""
                INSERT INTO seasons (name, start_at, end_at, status)
                VALUES (?, now() - interval '60 days', now() + interval '300 days', 'ACTIVE') RETURNING id
                """, UUID.class, "Temporada " + UUID.randomUUID());
        UUID fee = jdbc.queryForObject("""
                INSERT INTO fees (season_id, concept, name, amount, valid_from)
                VALUES (?, 'MONTHLY', 'Mensualidad tablero', 100000, current_date - 90) RETURNING id
                """, UUID.class, season);
        UUID category = jdbc.queryForObject("""
                INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 20) RETURNING id
                """, UUID.class, "Tablero " + UUID.randomUUID().toString().substring(0, 6), BIRTH_YEAR, BIRTH_YEAR + 1);
        UUID venue = jdbc.queryForObject("INSERT INTO venues (name) VALUES (?) RETURNING id", UUID.class,
                "Cancha tablero " + UUID.randomUUID());

        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Mateo', 'Tablero', 'TI', ?, DATE '2012-03-01', 'ACTIVE') RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "dashboard-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "DB-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);

        // cargo vencido hace 10 días, con un pago parcial de 30.000: debe 70.000
        LocalDate due = LocalDate.now().minusDays(10);
        UUID charge = jdbc.queryForObject("""
                INSERT INTO charges (player_id, fee_id, period, amount, due_date) VALUES (?, ?, ?, 100000, ?) RETURNING id
                """, UUID.class, player, fee, due.withDayOfMonth(1), due);
        jdbc.update("""
                INSERT INTO payments (player_id, fee_id, charge_id, amount, method, paid_at, recorded_by)
                VALUES (?, ?, ?, 30000, 'CASH', now(), ?)
                """, player, fee, charge, coordinator);

        UUID match = jdbc.queryForObject("""
                INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at)
                VALUES ('FRIENDLY_MATCH', ?, ?, now() + interval '3 days', now() + interval '3 days 2 hours') RETURNING id
                """, UUID.class, category, venue);
        jdbc.update("INSERT INTO matches (event_id, opponent) VALUES (?, 'Rivales del Norte')", match);

        JsonNode overview = overview(token("COORDINATOR"));

        JsonNode team = find(overview.get("teams"), "categoryId", category.toString());
        assertThat(team.get("rosterSize").asInt()).isEqualTo(1);
        assertThat(team.get("enabledCount").asInt()).isZero(); // debe algo vencido: no está habilitado
        assertThat(team.get("headCoach").asText()).isEqualTo("Entrenador Prueba");
        assertThat(team.get("headCoachId").asText()).isEqualTo(coach.toString());
        assertThat(team.get("debt").decimalValue()).isEqualByComparingTo("70000");
        assertThat(team.get("nextMatch").get("opponent").asText()).isEqualTo("Rivales del Norte");

        JsonNode row = find(overview.get("players"), "playerId", player.toString());
        assertThat(row.get("fullName").asText()).isEqualTo("Mateo Tablero");
        assertThat(row.get("enabled").asBoolean()).isFalse();
        assertThat(row.get("pendingCharge").get("outstanding").decimalValue()).isEqualByComparingTo("70000");

        JsonNode event = find(overview.get("events"), "id", match.toString());
        assertThat(event.get("eventType").asText()).isEqualTo("FRIENDLY_MATCH");
        assertThat(event.get("opponent").asText()).isEqualTo("Rivales del Norte");
    }

    @Test
    void aCoachCannotReadTheClubDashboardAndAnonymousIsRejected() throws Exception {
        String coachToken = token("COACH");
        mvc.perform(get(API + "/dashboard/club").header("Authorization", "Bearer " + coachToken))
                .andExpect(status().isForbidden());
        mvc.perform(get(API + "/dashboard/club")).andExpect(status().isUnauthorized());
    }
}
