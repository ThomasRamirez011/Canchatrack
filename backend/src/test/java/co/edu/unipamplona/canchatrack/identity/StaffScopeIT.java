package co.edu.unipamplona.canchatrack.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Alcance del personal deportivo contra PostgreSQL real: el entrenador titular ve datos básicos, asistencia y partidos
 * de sus jugadores; el evaluador asignado, solo los datos básicos; ninguno ve pagos ni identidad del jugador, y no ven
 * a jugadores ajenos (404, igual que si no existieran).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StaffScopeIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(4400);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    private record Account(UUID id, String token) {
    }

    private Account account(String roleCode) throws Exception {
        String email = roleCode.toLowerCase() + "-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Usuario de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        String body = mvc.perform(post(API + "/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Account(id, json.readTree(body).get("accessToken").asString());
    }

    private ResultActions getJson(String path, Account who) throws Exception {
        return mvc.perform(get(API + path).header("Authorization", "Bearer " + who.token()));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private UUID newPlayer() {
        return jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Luis', 'Pérez', 'TI', ?, DATE '2013-03-01', 'ACTIVE') RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    private UUID newMinute(UUID player) {
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "scope-it/" + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "SC-" + UUID.randomUUID().toString().substring(0, 8), player, file);
    }

    /** Asigna el jugador a una categoría nueva con ese entrenador titular. */
    private void coach(UUID player, Account coach) {
        int year = NEXT_YEAR.getAndAdd(3);
        UUID category = jdbc.queryForObject(
                "INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 30) RETURNING id",
                UUID.class, "Alcance " + UUID.randomUUID(), year, year + 1);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach.id(), newMinute(player));
    }

    private void evaluate(UUID player, Account evaluator) {
        jdbc.update("INSERT INTO evaluator_assignments (player_id, evaluator_id, minute_id) VALUES (?, ?, ?)",
                player, evaluator.id(), newMinute(player));
    }

    private List<UUID> listedIds(Account who) throws Exception {
        JsonNode page = body(getJson("/players?size=100", who).andExpect(status().isOk()));
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(n -> ids.add(UUID.fromString(n.get("id").asString())));
        return ids;
    }

    @Test
    void headCoachSeesTheBasicDataOfTheirPlayerWithoutIdentityOrEnrollments() throws Exception {
        UUID mine = newPlayer();
        UUID other = newPlayer();
        Account coach = account("COACH");
        coach(mine, coach);
        coach(other, account("COACH"));

        JsonNode view = body(getJson("/players/" + mine, coach).andExpect(status().isOk()));
        assertThat(view.get("id").asString()).isEqualTo(mine.toString());
        assertThat(view.get("firstName").asString()).isEqualTo("Luis");
        assertThat(view.has("documentNumber")).isFalse();
        assertThat(view.has("documentType")).isFalse();
        assertThat(view.get("enrollments").size()).isEqualTo(0);

        getJson("/players/" + other, coach).andExpect(status().isNotFound());
    }

    @Test
    void coordinatorStillSeesTheIdentityDocument() throws Exception {
        UUID player = newPlayer();
        Account coordinator = account("COORDINATOR");

        JsonNode view = body(getJson("/players/" + player, coordinator).andExpect(status().isOk()));
        assertThat(view.get("documentNumber").asString()).startsWith("TI-");
    }

    @Test
    void listingShowsOnlyTheirOwnPlayers() throws Exception {
        UUID mine = newPlayer();
        UUID alsoMine = newPlayer();
        UUID other = newPlayer();
        Account coach = account("COACH");
        coach(mine, coach);
        coach(alsoMine, coach);
        coach(other, account("COACH"));

        List<UUID> ids = listedIds(coach);

        assertThat(ids).hasSize(2);
        assertThat(ids).contains(mine);
        assertThat(ids).contains(alsoMine);
        assertThat(ids).doesNotContain(other);
    }

    @Test
    void headCoachSeesAttendanceAndMatchesOfOwnPlayersOnly() throws Exception {
        UUID mine = newPlayer();
        UUID other = newPlayer();
        Account coach = account("COACH");
        coach(mine, coach);
        coach(other, account("COACH"));

        getJson("/players/" + mine + "/attendance", coach).andExpect(status().isOk());
        getJson("/players/" + mine + "/matches", coach).andExpect(status().isOk());
        getJson("/players/" + other + "/attendance", coach).andExpect(status().isNotFound());
        getJson("/players/" + other + "/matches", coach).andExpect(status().isNotFound());
    }

    @Test
    void headCoachDoesNotSeeThePaymentAccount() throws Exception {
        UUID mine = newPlayer();
        Account coach = account("COACH");
        coach(mine, coach);

        assertThat(getJson("/players/" + mine + "/account", coach).andReturn().getResponse().getStatus())
                .isIn(403, 404);
    }

    @Test
    void assignedEvaluatorSeesOnlyTheBasicData() throws Exception {
        UUID evaluated = newPlayer();
        UUID notEvaluated = newPlayer();
        Account evaluator = account("EVALUATOR");
        evaluate(evaluated, evaluator);

        JsonNode view = body(getJson("/players/" + evaluated, evaluator).andExpect(status().isOk()));
        assertThat(view.has("documentNumber")).isFalse();
        assertThat(listedIds(evaluator)).containsExactly(evaluated);

        getJson("/players/" + notEvaluated, evaluator).andExpect(status().isNotFound());
        getJson("/players/" + evaluated + "/attendance", evaluator).andExpect(status().isForbidden());
        getJson("/players/" + evaluated + "/matches", evaluator).andExpect(status().isForbidden());
        getJson("/players/" + evaluated + "/account", evaluator).andExpect(status().isForbidden());
    }

    @Test
    void anEndedAssignmentRemovesTheAccess() throws Exception {
        UUID player = newPlayer();
        Account coach = account("COACH");
        coach(player, coach);
        getJson("/players/" + player, coach).andExpect(status().isOk());

        jdbc.update("UPDATE category_assignments SET valid_from = now() - interval '1 hour', valid_to = now() WHERE player_id = ?", player);

        getJson("/players/" + player, coach).andExpect(status().isNotFound());
        assertThat(listedIds(coach)).doesNotContain(player);
    }

    @Test
    void aGuardianStillSeesOnlyLinkedPlayers() throws Exception {
        UUID player = newPlayer();
        Account guardian = account("GUARDIAN");
        getJson("/players/" + player, guardian).andExpect(status().isNotFound());
    }
}
