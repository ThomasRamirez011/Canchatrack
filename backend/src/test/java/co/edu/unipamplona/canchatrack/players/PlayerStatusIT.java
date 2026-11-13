package co.edu.unipamplona.canchatrack.players;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Deshabilitar y rehabilitar jugadores contra PostgreSQL real: el cambio de estado, su rastro en {@code state_history}
 * y en la auditoría, y cómo lo ve el tablero (un jugador deshabilitado sigue en la plantilla, pero no habilitado).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PlayerStatusIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    /** Cada escenario necesita un rango de años propio: dos categorías activas no pueden solaparse. */
    private static final AtomicInteger NEXT_BIRTH_YEAR = new AtomicInteger(7600);

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
        String email = "status-" + UUID.randomUUID() + "@club.test";
        user(email, "Personal " + roleCode, roleCode);
        String body = mvc.perform(post(API + "/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    private record Scenario(UUID player, UUID category) {
    }

    private Scenario activePlayerInACategory() {
        UUID coach = user("coach-" + UUID.randomUUID() + "@club.test", "Entrenador Estado", "COACH");
        int birthYear = NEXT_BIRTH_YEAR.getAndAdd(10);
        UUID category = jdbc.queryForObject("""
                INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 20) RETURNING id
                """, UUID.class, "Estado " + UUID.randomUUID().toString().substring(0, 6), birthYear, birthYear + 1);
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Samuel', 'Estado', 'TI', ?, DATE '2012-03-01', 'ACTIVE') RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "status-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "ST-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);
        return new Scenario(player, category);
    }

    private ResultActions suspend(UUID player, String bearer, Object body) throws Exception {
        return mvc.perform(post(API + "/players/" + player + "/suspension").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions reinstate(UUID player, String bearer) throws Exception {
        return mvc.perform(post(API + "/players/" + player + "/reinstatement").header("Authorization", "Bearer " + bearer));
    }

    private JsonNode dashboardRow(String bearer, UUID player) throws Exception {
        String body = mvc.perform(get(API + "/dashboard/club").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return StreamSupport.stream(json.readTree(body).get("players").spliterator(), false)
                .filter(n -> player.toString().equals(n.get("playerId").asText())).findFirst().orElseThrow();
    }

    private String playerStatus(UUID player) {
        return jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, player);
    }

    @Test
    void suspendsAndReinstatesAPlayerLeavingHistoryAndAudit() throws Exception {
        String coordinator = token("COORDINATOR");
        Scenario scenario = activePlayerInACategory();

        JsonNode suspended = json.readTree(suspend(scenario.player(), coordinator, Map.of("reason", "Lesión de rodilla"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(suspended.get("previousStatus").asText()).isEqualTo("ACTIVE");
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(playerStatus(scenario.player())).isEqualTo("SUSPENDED");

        // sigue en la plantilla del tablero, pero deshabilitado
        JsonNode row = dashboardRow(coordinator, scenario.player());
        assertThat(row.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(row.get("enabled").asBoolean()).isFalse();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM state_history WHERE entity_type = 'player' AND entity_id = ?
                  AND transition_code = 'T-13' AND from_state = 'ACTIVE' AND to_state = 'SUSPENDED' AND reason = 'Lesión de rodilla'
                """, Integer.class, scenario.player())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE action = 'PLAYER_SUSPENDED' AND entity_id = ? AND outcome = 'SUCCESS'
                  AND reason = 'Lesión de rodilla'
                """, Integer.class, scenario.player())).isEqualTo(1);

        // no se puede suspender dos veces
        suspend(scenario.player(), coordinator, Map.of("reason", "Otra vez")).andExpect(status().isConflict());

        JsonNode reinstated = json.readTree(reinstate(scenario.player(), coordinator)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(reinstated.get("previousStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(reinstated.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(playerStatus(scenario.player())).isEqualTo("ACTIVE");
        assertThat(dashboardRow(coordinator, scenario.player()).get("enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM state_history WHERE entity_id = ? AND transition_code = 'T-14'
                """, Integer.class, scenario.player())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'PLAYER_REINSTATED' AND entity_id = ?",
                Integer.class, scenario.player())).isEqualTo(1);

        // un jugador activo no se rehabilita
        reinstate(scenario.player(), coordinator).andExpect(status().isConflict());
    }

    @Test
    void validatesTheReasonAndTheTargetAndRestrictsTheEndpointsToTheCoordinator() throws Exception {
        String coordinator = token("COORDINATOR");
        Scenario scenario = activePlayerInACategory();

        suspend(scenario.player(), coordinator, Map.of()).andExpect(status().isUnprocessableEntity());
        suspend(scenario.player(), coordinator, Map.of("reason", "   ")).andExpect(status().isUnprocessableEntity());
        assertThat(playerStatus(scenario.player())).isEqualTo("ACTIVE");

        suspend(UUID.randomUUID(), coordinator, Map.of("reason", "No existe")).andExpect(status().isNotFound());
        reinstate(UUID.randomUUID(), coordinator).andExpect(status().isNotFound());

        String coach = token("COACH");
        suspend(scenario.player(), coach, Map.of("reason", "Sin permiso")).andExpect(status().isForbidden());
        reinstate(scenario.player(), coach).andExpect(status().isForbidden());
        mvc.perform(post(API + "/players/" + scenario.player() + "/suspension").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"anónimo\"}")).andExpect(status().isUnauthorized());
        assertThat(playerStatus(scenario.player())).isEqualTo("ACTIVE");
    }
}
