package co.edu.unipamplona.canchatrack.evaluations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Evaluación y ascenso contra PostgreSQL real: rondas, conceptos independientes, unanimidad, cierre, plan de seguimiento
 * y ascenso con historial (CU-07, CU-08; RN-08..RN-12). Cada prueba crea su propio jugador, panel y categorías.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EvaluationsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(2400);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    // ---------- utilidades ----------

    private ResultActions send(MockHttpServletRequestBuilder request, Object body, String bearer) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions postJson(String path, Object body, String bearer) throws Exception {
        return send(post(API + path), body, bearer);
    }

    private ResultActions putJson(String path, Object body, String bearer) throws Exception {
        return send(put(API + path), body, bearer);
    }

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer);
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
                .get("accessToken").asString();
        return new Account(id, token);
    }

    /** Jugador, categoría de origen (con la destino como siguiente) y panel de evaluadores. */
    private record Scenario(Account coordinator, List<Account> panel, UUID player, UUID source, UUID target) {
    }

    private UUID category(String name, int year, int capacity, UUID next) {
        return jdbc.queryForObject("""
                INSERT INTO categories (name, birth_year_min, birth_year_max, capacity, next_category_id)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, name + " " + UUID.randomUUID(), year, year + 1, capacity, next);
    }

    private UUID assignedPlayer(UUID category, UUID coach, String status, List<Account> panel) {
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Ana', 'Ruiz', 'TI', ?, DATE '2012-03-01', ?) RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), status);
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "eval-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "EV-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);
        for (Account evaluator : panel) {
            jdbc.update("INSERT INTO evaluator_assignments (player_id, evaluator_id, minute_id) VALUES (?, ?, ?)",
                    player, evaluator.id(), minute);
        }
        return player;
    }

    private Scenario scenario(String playerStatus, int panelSize, int targetCapacity) throws Exception {
        int year = NEXT_YEAR.getAndAdd(6);
        UUID target = category("Destino", year + 3, targetCapacity, null);
        UUID source = category("Origen", year, 30, target);
        Account coordinator = account("COORDINATOR");
        UUID coach = account("COACH").id();
        List<Account> panel = new ArrayList<>();
        for (int i = 0; i < panelSize; i++) {
            panel.add(account("EVALUATOR"));
        }
        return new Scenario(coordinator, panel, assignedPlayer(source, coach, playerStatus, panel), source, target);
    }

    private Map<String, Object> openRequest(int dueInDays) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("periodStart", "2026-01-01");
        request.put("periodEnd", "2026-06-30");
        request.put("dueAt", Instant.now().plus(dueInDays, ChronoUnit.DAYS).toString());
        return request;
    }

    private UUID openRound(Scenario s) throws Exception {
        return UUID.fromString(body(postJson("/players/" + s.player() + "/evaluation-rounds", openRequest(30), s.coordinator().token())
                .andExpect(status().isCreated())).get("id").asString());
    }

    private Map<String, Object> verdict(String verdict) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("verdict", verdict);
        if (!"FAVORABLE".equals(verdict)) {
            request.put("justification", "Debe mejorar la condición física");
            request.put("actions", "Plan de acondicionamiento de 6 semanas");
        }
        request.put("criteria", Map.of("tecnica", 4, "disciplina", 5));
        return request;
    }

    private JsonNode decide(UUID round, Account evaluator, String verdict) throws Exception {
        return body(putJson("/evaluation-rounds/" + round + "/decision", verdict(verdict), evaluator.token()).andExpect(status().isOk()));
    }

    private String playerStatus(UUID player) {
        return jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, player);
    }

    private Map<String, Object> promotion(UUID coach) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("headCoachId", coach);
        return request;
    }

    /** Ronda abierta con los tres conceptos favorables ya emitidos y cerrada con ascenso aprobado. */
    private UUID approvedRound(Scenario s) throws Exception {
        UUID round = openRound(s);
        for (Account evaluator : s.panel()) {
            decide(round, evaluator, "FAVORABLE");
        }
        body(postJson("/evaluation-rounds/" + round + "/close", Map.of(), s.coordinator().token()).andExpect(status().isOk()));
        return round;
    }

    // ---------- pruebas ----------

    @Test
    void unanimousFavorableRoundApprovesAndPromotionMovesTheCategoryKeepingHistory() throws Exception {
        Scenario s = scenario("ACTIVE", 3, 30);
        Account coordinator = s.coordinator();
        UUID round = openRound(s);
        assertThat(playerStatus(s.player())).isEqualTo("IN_EVALUATION");

        // Una sola ronda abierta por jugador.
        assertThat(body(postJson("/players/" + s.player() + "/evaluation-rounds", openRequest(30), coordinator.token())
                .andExpect(status().isConflict())).get("code").asString()).isEqualTo("PLAYER_STATE_CONFLICT");
        // Sin conceptos y antes del vencimiento no se puede cerrar.
        assertThat(body(postJson("/evaluation-rounds/" + round + "/close", Map.of(), coordinator.token())
                .andExpect(status().isConflict())).get("code").asString()).isEqualTo("RESOURCE_STATE_CONFLICT");

        decide(round, s.panel().get(0), "FAVORABLE");

        // Independencia: cada evaluador ve solo su concepto mientras la ronda está abierta; la coordinación ve todos.
        JsonNode own = body(getJson("/evaluation-rounds/" + round, s.panel().get(0).token()).andExpect(status().isOk()));
        assertThat(own.get("decisions").size()).isEqualTo(1);
        assertThat(own.hasNonNull("outcome")).isFalse();
        assertThat(body(getJson("/evaluation-rounds/" + round, s.panel().get(1).token()).andExpect(status().isOk()))
                .get("decisions").size()).isZero();
        JsonNode seen = body(getJson("/evaluation-rounds/" + round, coordinator.token()).andExpect(status().isOk()));
        assertThat(seen.get("outcome").asString()).isEqualTo("PENDING");

        decide(round, s.panel().get(1), "FAVORABLE");
        decide(round, s.panel().get(2), "FAVORABLE");
        assertThat(body(getJson("/evaluation-rounds/" + round, coordinator.token()).andExpect(status().isOk()))
                .get("outcome").asString()).isEqualTo("APPROVED");

        // Nadie decide en nombre de otro: la coordinación no tiene rol de evaluador y un evaluador ajeno al panel tampoco.
        putJson("/evaluation-rounds/" + round + "/decision", verdict("FAVORABLE"), coordinator.token())
                .andExpect(status().isForbidden());
        Account outsider = account("EVALUATOR");
        assertThat(body(putJson("/evaluation-rounds/" + round + "/decision", verdict("FAVORABLE"), outsider.token())
                .andExpect(status().isUnprocessableEntity())).get("code").asString()).isEqualTo("PANEL_INVALID");
        getJson("/evaluation-rounds/" + round, outsider.token()).andExpect(status().isNotFound());

        // Ascenso aún no ejecutable: la ronda no está cerrada.
        postJson("/players/" + s.player() + "/promotion", promotion(account("COACH").id()), coordinator.token())
                .andExpect(status().isConflict());

        JsonNode closed = body(postJson("/evaluation-rounds/" + round + "/close", Map.of(), coordinator.token()).andExpect(status().isOk()));
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("result").asString()).isEqualTo("APPROVED");
        assertThat(closed.get("decisions").size()).isEqualTo(3);
        assertThat(playerStatus(s.player())).isEqualTo("PROMOTION_APPROVED");

        // Cerrada la ronda, los conceptos no cambian (RN-10) y no se puede cerrar de nuevo.
        assertThat(body(putJson("/evaluation-rounds/" + round + "/decision", verdict("UNFAVORABLE"), s.panel().get(0).token())
                .andExpect(status().isConflict())).get("code").asString()).isEqualTo("ROUND_CLOSED");
        postJson("/evaluation-rounds/" + round + "/close", Map.of(), coordinator.token()).andExpect(status().isConflict());

        // Ascenso: la categoría siguiente se toma por defecto y el historial se conserva (CA-14).
        UUID newCoach = account("COACH").id();
        JsonNode promoted = body(postJson("/players/" + s.player() + "/promotion", promotion(newCoach), coordinator.token())
                .andExpect(status().isOk()));
        assertThat(promoted.get("categoryId").asString()).isEqualTo(s.target().toString());
        assertThat(promoted.get("previousCategoryId").asString()).isEqualTo(s.source().toString());
        assertThat(playerStatus(s.player())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category_assignments WHERE player_id = ?", Integer.class, s.player())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT category_id FROM category_assignments WHERE player_id = ? AND valid_to IS NULL AND kind = 'PROMOTION'
                  AND promotion_round_id = ?
                """, UUID.class, s.player(), round)).isEqualTo(s.target());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evaluator_assignments WHERE player_id = ? AND valid_to IS NULL",
                Integer.class, s.player())).isEqualTo(3);
        postJson("/players/" + s.player() + "/promotion", promotion(newCoach), coordinator.token()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id IN (?, ?) AND action IN (?, ?, ?, ?)",
                Integer.class, round, s.player(), "EVALUATION_ROUND_OPENED", "EVALUATOR_DECISION_RECORDED",
                "EVALUATION_ROUND_CLOSED", "PLAYER_PROMOTED")).isEqualTo(6);
    }

    @Test
    void nonFavorableConceptNeedsReasonsAdjustsWithHistoryAndClosesWithAFollowUpPlan() throws Exception {
        Scenario s = scenario("ACTIVE", 3, 30);
        UUID round = openRound(s);

        // RN-11: sin justificación ni acciones concretas no se registra.
        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("verdict", "UNFAVORABLE");
        JsonNode rejected = body(putJson("/evaluation-rounds/" + round + "/decision", bare, s.panel().get(0).token())
                .andExpect(status().isUnprocessableEntity()));
        assertThat(rejected.get("code").asString()).isEqualTo("NEGATIVE_REASON_REQUIRED");
        assertThat(rejected.get("errors").findValuesAsString("field")).containsExactlyInAnyOrder("justification", "actions");

        JsonNode first = decide(round, s.panel().get(0), "UNFAVORABLE");
        JsonNode adjusted = decide(round, s.panel().get(0), "FOLLOW_UP_REQUIRED");
        assertThat(adjusted.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(adjusted.get("verdict").asString()).isEqualTo("FOLLOW_UP_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evaluator_decision_history WHERE decision_id = ?",
                Integer.class, UUID.fromString(first.get("id").asString()))).isEqualTo(1);
        decide(round, s.panel().get(1), "FAVORABLE");
        decide(round, s.panel().get(2), "FAVORABLE");
        assertThat(body(getJson("/evaluation-rounds/" + round, s.coordinator().token()).andExpect(status().isOk()))
                .get("outcome").asString()).isEqualTo("FOLLOW_UP");

        // Cerrar sin ascenso exige el plan de seguimiento, que define la coordinación.
        JsonNode noPlan = body(postJson("/evaluation-rounds/" + round + "/close", Map.of(), s.coordinator().token())
                .andExpect(status().isUnprocessableEntity()));
        assertThat(noPlan.get("errors").findValuesAsString("field")).containsExactly("followUpPlan");
        assertThat(playerStatus(s.player())).isEqualTo("IN_EVALUATION");

        JsonNode closed = body(postJson("/evaluation-rounds/" + round + "/close", Map.of("followUpPlan", "Acondicionamiento y nueva ronda en 3 meses"),
                s.coordinator().token()).andExpect(status().isOk()));
        assertThat(closed.get("result").asString()).isEqualTo("FOLLOW_UP");
        assertThat(closed.get("followUpPlan").asString()).startsWith("Acondicionamiento");
        assertThat(playerStatus(s.player())).isEqualTo("ACTIVE");
        postJson("/players/" + s.player() + "/promotion", promotion(account("COACH").id()), s.coordinator().token())
                .andExpect(status().isConflict());

        // El jugador vuelve a ser evaluable en una ronda nueva y conserva la anterior en su historial.
        postJson("/players/" + s.player() + "/evaluation-rounds", openRequest(30), s.coordinator().token()).andExpect(status().isCreated());
        assertThat(body(getJson("/players/" + s.player() + "/evaluation-rounds", s.panel().get(0).token()).andExpect(status().isOk())).size())
                .isEqualTo(2);
    }

    @Test
    void changingThePeriodInvalidatesConceptsIssuedOverThePreviousOne() throws Exception {
        Scenario s = scenario("ACTIVE", 3, 30);
        UUID round = openRound(s);
        for (Account evaluator : s.panel()) {
            decide(round, evaluator, "FAVORABLE");
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("periodStart", "2026-02-01");
        change.put("periodEnd", "2026-07-31");

        // El cambio de periodo exige motivo.
        assertThat(body(putJson("/evaluation-rounds/" + round + "/period", change, s.coordinator().token())
                .andExpect(status().isUnprocessableEntity())).get("errors").findValuesAsString("field")).containsExactly("reason");
        change.put("reason", "El torneo de referencia se aplazó un mes");
        JsonNode updated = body(putJson("/evaluation-rounds/" + round + "/period", change, s.coordinator().token()).andExpect(status().isOk()));
        assertThat(updated.get("periodStart").asString()).isEqualTo("2026-02-01");
        assertThat(updated.get("outcome").asString()).isEqualTo("PENDING");
        assertThat(updated.get("decisions").findValuesAsString("current")).containsOnly("false");
        postJson("/evaluation-rounds/" + round + "/close", Map.of(), s.coordinator().token()).andExpect(status().isConflict());

        // Los evaluadores vuelven a emitir sobre el periodo nuevo y la unanimidad se recupera.
        for (Account evaluator : s.panel()) {
            decide(round, evaluator, "FAVORABLE");
        }
        JsonNode again = body(getJson("/evaluation-rounds/" + round, s.coordinator().token()).andExpect(status().isOk()));
        assertThat(again.get("outcome").asString()).isEqualTo("APPROVED");
        assertThat(again.get("decisions").findValuesAsString("current")).containsOnly("true");
    }

    @Test
    void aRoundWithMissingConceptsClosesWithFollowUpOnceItsDueDateHasPassed() throws Exception {
        Scenario s = scenario("ACTIVE", 3, 30);
        UUID round = openRound(s);
        decide(round, s.panel().get(0), "FAVORABLE");
        postJson("/evaluation-rounds/" + round + "/close", Map.of("followUpPlan", "plan"), s.coordinator().token())
                .andExpect(status().isConflict());

        jdbc.update("UPDATE evaluation_rounds SET due_at = now() - interval '1 day' WHERE id = ?", round);
        JsonNode closed = body(postJson("/evaluation-rounds/" + round + "/close", Map.of("followUpPlan", "Faltaron conceptos; reprogramar"),
                s.coordinator().token()).andExpect(status().isOk()));
        assertThat(closed.get("result").asString()).isEqualTo("FOLLOW_UP");
        assertThat(playerStatus(s.player())).isEqualTo("ACTIVE");
    }

    @Test
    void roundsOnlyOpenForActivePlayersWithAFullPanelAndValidPeriods() throws Exception {
        Scenario submitted = scenario("SUBMITTED", 3, 30);
        assertThat(body(postJson("/players/" + submitted.player() + "/evaluation-rounds", openRequest(30), submitted.coordinator().token())
                .andExpect(status().isConflict())).get("code").asString()).isEqualTo("PLAYER_STATE_CONFLICT");

        Scenario shortPanel = scenario("ACTIVE", 2, 30);
        assertThat(body(postJson("/players/" + shortPanel.player() + "/evaluation-rounds", openRequest(30), shortPanel.coordinator().token())
                .andExpect(status().isUnprocessableEntity())).get("code").asString()).isEqualTo("PANEL_INVALID");
        assertThat(playerStatus(shortPanel.player())).isEqualTo("ACTIVE");

        Scenario valid = scenario("ACTIVE", 3, 30);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("periodStart", "2026-07-01");
        bad.put("periodEnd", "2026-01-01");
        bad.put("dueAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        JsonNode errors = body(postJson("/players/" + valid.player() + "/evaluation-rounds", bad, valid.coordinator().token())
                .andExpect(status().isUnprocessableEntity()));
        assertThat(errors.get("errors").findValuesAsString("field")).containsExactlyInAnyOrder("periodEnd", "dueAt");
        assertThat(playerStatus(valid.player())).isEqualTo("ACTIVE");

        // Solo la coordinación abre rondas; el personal ajeno al panel no las consulta.
        Account coach = account("COACH");
        postJson("/players/" + valid.player() + "/evaluation-rounds", openRequest(30), coach.token()).andExpect(status().isForbidden());
        getJson("/players/" + valid.player() + "/evaluation-rounds", coach.token()).andExpect(status().isNotFound());
        getJson("/players/" + valid.player() + "/evaluation-rounds", account("EVALUATOR").token()).andExpect(status().isNotFound());
        getJson("/players/" + valid.player() + "/evaluation-rounds", valid.panel().get(0).token()).andExpect(status().isOk());
    }

    @Test
    void promotionNeedsRoomInTheTargetCategoryAndAnActiveCoach() throws Exception {
        Scenario s = scenario("ACTIVE", 3, 1);
        // Otro jugador ocupa el único cupo de la categoría destino.
        assignedPlayer(s.target(), account("COACH").id(), "ACTIVE", List.of());
        approvedRound(s);
        UUID newCoach = account("COACH").id();

        JsonNode full = body(postJson("/players/" + s.player() + "/promotion", promotion(newCoach), s.coordinator().token())
                .andExpect(status().isConflict()));
        assertThat(full.get("code").asString()).isEqualTo("CATEGORY_FULL");
        assertThat(playerStatus(s.player())).isEqualTo("PROMOTION_APPROVED");

        // Entrenador que no es entrenador: validación; y el jugador sigue pendiente de ascenso.
        JsonNode badCoach = body(postJson("/players/" + s.player() + "/promotion", promotion(s.panel().get(0).id()), s.coordinator().token())
                .andExpect(status().isUnprocessableEntity()));
        assertThat(badCoach.get("errors").findValuesAsString("field")).containsExactly("headCoachId");

        // Con cupo, ya se puede ascender a la categoría destino indicada explícitamente.
        jdbc.update("UPDATE categories SET capacity = 5 WHERE id = ?", s.target());
        Map<String, Object> request = promotion(newCoach);
        request.put("targetCategoryId", s.target());
        postJson("/players/" + s.player() + "/promotion", request, s.coordinator().token()).andExpect(status().isOk());
        assertThat(playerStatus(s.player())).isEqualTo("ACTIVE");
    }
}
