package co.edu.unipamplona.canchatrack.players;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Decisión del Comité Técnico (T-01 asignar, T-02 rechazar) contra PostgreSQL real: acta, panel de tres evaluadores,
 * cupo bajo concurrencia, cierre del proceso anterior y auditoría. Las pruebas no son transaccionales y comparten la
 * base, así que cada categoría usa años propios (desde 2030, lejos de los de {@code CategoriesIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CommitteeIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(2030);

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

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private record Staff(UUID id, String email) {
    }

    /** Cuenta del personal con un rol; sin iniciar sesión (sirve para coach y evaluadores del panel). */
    private Staff staffAccount(String roleCode) {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Personal de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return new Staff(id, email);
    }

    private String token(Staff staff) throws Exception {
        return body(postJson("/auth/login", Map.of("email", staff.email(), "password", PASSWORD), null)
                .andExpect(status().isOk())).get("accessToken").asString();
    }

    private String staffToken(String roleCode) throws Exception {
        return token(staffAccount(roleCode));
    }

    private UUID userIdOf(String token) throws Exception {
        return UUID.fromString(body(getJson("/auth/me", token).andExpect(status().isOk())).get("id").asString());
    }

    private String createCategory(int capacity) throws Exception {
        String coordinator = staffToken("COORDINATOR");
        int first = NEXT_YEAR.getAndAdd(3);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "Comité " + UUID.randomUUID());
        request.put("birthYearMin", first);
        request.put("birthYearMax", first + 1);
        request.put("capacity", capacity);
        return body(postJson("/categories", request, coordinator).andExpect(status().isCreated())).get("id").asString();
    }

    private UUID newPlayer() {
        return jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date)
                VALUES ('Juan', 'Pérez', 'TI', ?, DATE '2014-06-01') RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    private UUID storedFile() {
        return jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 1024, repeat('a', 64)) RETURNING id
                """, UUID.class, "committee-it/" + UUID.randomUUID());
    }

    /** Panel válido: entrenador titular (COACH) y tres evaluadores (EVALUATOR, EVALUATOR, COMMITTEE). */
    private record Panel(UUID coach, List<UUID> evaluators) {
    }

    private Panel panel() {
        return new Panel(staffAccount("COACH").id(),
                List.of(staffAccount("EVALUATOR").id(), staffAccount("EVALUATOR").id(), staffAccount("COMMITTEE").id()));
    }

    private Map<String, Object> assignRequest(String minuteNumber, UUID file, String categoryId, Panel panel) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("minuteNumber", minuteNumber);
        request.put("meetingDate", LocalDate.now().minusDays(1).toString());
        request.put("attendees", List.of("Ana Gómez", "Luis \"El Profe\" Pérez"));
        request.put("minuteFileId", file);
        request.put("categoryId", categoryId);
        request.put("headCoachId", panel.coach());
        request.put("evaluatorIds", panel.evaluators());
        return request;
    }

    private String playerStatus(UUID player) {
        return jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, player);
    }

    private long count(String sql, Object... args) {
        Long total = jdbc.queryForObject(sql, Long.class, args);
        return total == null ? 0 : total;
    }

    private long countAudit(String action, UUID actor) {
        return count("SELECT count(*) FROM audit_events WHERE action = ? AND actor_id = ?", action, actor);
    }

    private static List<String> errorFields(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        problem.get("errors").forEach(e -> fields.add(e.get("field").asString()));
        return fields;
    }

    /** Acudiente con vínculo vigente con el jugador; devuelve el correo de su cuenta. */
    private String guardianOf(UUID player) {
        Staff account = staffAccount("GUARDIAN");
        UUID guardian = jdbc.queryForObject(
                "INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                UUID.class, account.id(), "cc-" + UUID.randomUUID().toString().substring(0, 20));
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, true)", player, guardian);
        return account.email();
    }

    private List<Map<String, Object>> noticesOf(UUID player, String type) {
        return jdbc.queryForList("""
                SELECT recipient_email, subject, payload::text AS payload, dedup_key
                FROM notifications WHERE related_entity_id = ? AND notification_type = ?
                """, player, type);
    }

    // ---------- aviso del resultado a los acudientes (RF-047) ----------

    @Test
    void theGuardianIsToldTheCategoryWhenTheCommitteeAssignsAndNothingAboutThePanel() throws Exception {
        UUID player = newPlayer();
        String email = guardianOf(player);
        Panel panel = panel();
        String committee = staffToken("COMMITTEE");

        postJson("/players/" + player + "/assignments", assignRequest("ACTA-N1", storedFile(), createCategory(5), panel), committee)
                .andExpect(status().isCreated());

        List<Map<String, Object>> notices = noticesOf(player, "COMMITTEE_ASSIGNED");
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).get("recipient_email")).isEqualTo(email);
        String payload = notices.get(0).get("payload").toString();
        assertThat(payload).contains("Juan Pérez", "categoryName");
        assertThat(payload).doesNotContain("ACTA-N1", panel.coach().toString(), "Luis");
        assertThat(noticesOf(player, "COMMITTEE_REJECTED")).isEmpty();
    }

    @Test
    void aRejectionIsToldWithoutTheReasonAndAFailedDecisionSendsNothing() throws Exception {
        UUID player = newPlayer();
        String email = guardianOf(player);
        String committee = staffToken("COMMITTEE");

        postJson("/players/" + player + "/rejections", Map.of("minuteNumber", "ACTA-N2", "reason", " "), committee)
                .andExpect(status().isUnprocessableEntity());
        assertThat(noticesOf(player, "COMMITTEE_REJECTED")).isEmpty();

        Map<String, Object> rejection = new LinkedHashMap<>();
        rejection.put("minuteNumber", "ACTA-N2");
        rejection.put("meetingDate", LocalDate.now().minusDays(1).toString());
        rejection.put("attendees", List.of("Ana Gómez"));
        rejection.put("minuteFileId", storedFile());
        rejection.put("reason", "Información médica reservada");
        postJson("/players/" + player + "/rejections", rejection, committee).andExpect(status().isCreated());

        List<Map<String, Object>> notices = noticesOf(player, "COMMITTEE_REJECTED");
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).get("recipient_email")).isEqualTo(email);
        assertThat(notices.get(0).get("payload").toString()).contains("Juan Pérez").doesNotContain("médica", "reservada");
        assertThat(notices.get(0).get("subject").toString()).doesNotContain("médica");
    }

    // ---------- autorización ----------

    @Test
    void onlyTheCommitteeDecidesAndOnlyCoordinatorAndCommitteeRead() throws Exception {
        UUID player = newPlayer();
        Map<String, Object> request = assignRequest("ACTA-A", storedFile(), createCategory(5), panel());
        Map<String, Object> rejection = Map.of("minuteNumber", "ACTA-A", "reason", "x");

        postJson("/players/" + player + "/assignments", request, null).andExpect(status().isUnauthorized());
        for (String role : List.of("COORDINATOR", "COACH", "EVALUATOR")) {
            String token = staffToken(role);
            postJson("/players/" + player + "/assignments", request, token).andExpect(status().isForbidden());
            postJson("/players/" + player + "/rejections", rejection, token).andExpect(status().isForbidden());
        }
        getJson("/players/" + player + "/assignment", null).andExpect(status().isUnauthorized());
        getJson("/players/" + player + "/assignment", staffToken("COACH")).andExpect(status().isForbidden());
        getJson("/players/" + player + "/assignment", staffToken("COORDINATOR")).andExpect(status().isNotFound());
        getJson("/players/" + player + "/assignment", staffToken("COMMITTEE")).andExpect(status().isNotFound());
        assertThat(playerStatus(player)).isEqualTo("SUBMITTED");
    }

    // ---------- asignar ----------

    @Test
    void assigningStoresMinuteCoachAndThreeEvaluatorsAndAudits() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID committeeId = userIdOf(committee);
        UUID player = newPlayer();
        UUID file = storedFile();
        String categoryId = createCategory(5);
        Panel panel = panel();

        postJson("/players/" + player + "/assignments", assignRequest(" ACTA-10 ", file, categoryId, panel), committee)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerStatus").value("CATEGORY_ASSIGNED"))
                .andExpect(jsonPath("$.categoryId").value(categoryId))
                .andExpect(jsonPath("$.headCoachId").value(panel.coach().toString()))
                .andExpect(jsonPath("$.evaluatorIds.length()").value(3))
                .andExpect(jsonPath("$.minuteNumber").value("ACTA-10"))
                .andExpect(jsonPath("$.minuteFileId").value(file.toString()))
                .andExpect(jsonPath("$.capacityExceptionReason").doesNotExist());

        assertThat(playerStatus(player)).isEqualTo("CATEGORY_ASSIGNED");
        assertThat(count("SELECT count(*) FROM category_assignments WHERE player_id = ? AND valid_to IS NULL AND kind = 'COMMITTEE'", player)).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM evaluator_assignments WHERE player_id = ? AND valid_to IS NULL", player)).isEqualTo(3L);
        assertThat(count("SELECT count(*) FROM committee_minutes WHERE player_id = ? AND decision = 'ASSIGNED' AND minute_number = 'ACTA-10'", player)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT attendees::text FROM committee_minutes WHERE player_id = ?", String.class, player))
                .isEqualTo("[\"Ana Gómez\", \"Luis \\\"El Profe\\\" Pérez\"]");
        assertThat(countAudit("PLAYER_CATEGORY_ASSIGNED", committeeId)).isEqualTo(1L);

        getJson("/players/" + player + "/assignment", staffToken("COORDINATOR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(categoryId))
                .andExpect(jsonPath("$.evaluatorIds.length()").value(3));
    }

    @Test
    void everyFieldErrorIsReportedTogetherAndNothingIsWritten() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID player = newPlayer();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("minuteNumber", " ");
        request.put("meetingDate", LocalDate.now().plusDays(3).toString());
        request.put("attendees", List.of());
        request.put("minuteFileId", UUID.randomUUID());
        request.put("evaluatorIds", List.of(staffAccount("EVALUATOR").id()));

        JsonNode problem = body(postJson("/players/" + player + "/assignments", request, committee)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PANEL_INVALID")));

        assertThat(errorFields(problem)).isEqualTo(List.of("minuteNumber", "attendees", "meetingDate", "minuteFileId",
                "categoryId", "headCoachId", "evaluatorIds"));
        assertThat(playerStatus(player)).isEqualTo("SUBMITTED");
        assertThat(count("SELECT count(*) FROM committee_minutes WHERE player_id = ?", player)).isEqualTo(0L);
    }

    @Test
    void headCoachAndPanelMembersMustHoldAdmittedRolesWithActiveAccounts() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID player = newPlayer();
        String categoryId = createCategory(5);
        UUID guardianRole = staffAccount("GUARDIAN").id();
        UUID disabled = staffAccount("EVALUATOR").id();
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", disabled);
        Panel bad = new Panel(staffAccount("EVALUATOR").id(), List.of(staffAccount("EVALUATOR").id(), guardianRole, disabled));

        JsonNode problem = body(postJson("/players/" + player + "/assignments", assignRequest("ACTA-R", storedFile(), categoryId, bad), committee)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PANEL_INVALID")));

        assertThat(errorFields(problem)).isEqualTo(List.of("headCoachId", "evaluatorIds", "evaluatorIds"));
        assertThat(playerStatus(player)).isEqualTo("SUBMITTED");
    }

    @Test
    void aMissingOrInactiveCategoryIsAFieldError() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID player = newPlayer();
        UUID file = storedFile();
        Panel panel = panel();
        postJson("/players/" + player + "/assignments", assignRequest("ACTA-C", file, UUID.randomUUID().toString(), panel), committee)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'categoryId')]").isNotEmpty());

        String inactive = createCategory(5);
        jdbc.update("UPDATE categories SET active = false WHERE id = ?::uuid", inactive);
        postJson("/players/" + player + "/assignments", assignRequest("ACTA-C", file, inactive, panel), committee)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'categoryId')]").isNotEmpty());
        assertThat(playerStatus(player)).isEqualTo("SUBMITTED");
    }

    @Test
    void anUnknownPlayerIs404AndARepeatedDecisionIs409() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID file = storedFile();
        String categoryId = createCategory(5);
        Panel panel = panel();
        postJson("/players/" + UUID.randomUUID() + "/assignments", assignRequest("ACTA-N", file, categoryId, panel), committee)
                .andExpect(status().isNotFound());

        UUID player = newPlayer();
        postJson("/players/" + player + "/assignments", assignRequest("ACTA-N", file, categoryId, panel), committee)
                .andExpect(status().isCreated());
        postJson("/players/" + player + "/assignments", assignRequest("ACTA-N2", file, categoryId, panel), committee)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAYER_STATE_CONFLICT"));
        postJson("/players/" + player + "/rejections", Map.of("minuteNumber", "ACTA-N3", "meetingDate",
                LocalDate.now().minusDays(1).toString(), "attendees", List.of("Ana"), "minuteFileId", file, "reason", "tarde"), committee)
                .andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM committee_minutes WHERE player_id = ?", player)).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM category_assignments WHERE player_id = ?", player)).isEqualTo(1L);
    }

    @Test
    void aMinuteNumberAlreadyUsedForThatPlayerIsAFieldError() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID player = newPlayer();
        UUID file = storedFile();
        jdbc.update("INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, decision_reason, file_id) VALUES ('ACTA-DUP', DATE '2026-01-01', ?, 'REJECTED', 'previo', ?)", player, file);

        postJson("/players/" + player + "/assignments", assignRequest("ACTA-DUP", file, createCategory(5), panel()), committee)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'minuteNumber')]").isNotEmpty());
        assertThat(playerStatus(player)).isEqualTo("SUBMITTED");
        assertThat(count("SELECT count(*) FROM category_assignments WHERE player_id = ?", player)).isEqualTo(0L);
    }

    // ---------- cupo ----------

    @Test
    void aFullCategoryNeedsAnExceptionReasonAndTheReasonIsKept() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID committeeId = userIdOf(committee);
        UUID file = storedFile();
        String categoryId = createCategory(1);
        Panel panel = panel();
        postJson("/players/" + newPlayer() + "/assignments", assignRequest("ACTA-F1", file, categoryId, panel), committee)
                .andExpect(status().isCreated());

        UUID second = newPlayer();
        postJson("/players/" + second + "/assignments", assignRequest("ACTA-F2", file, categoryId, panel), committee)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_FULL"))
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.occupied").value(1));
        assertThat(playerStatus(second)).isEqualTo("SUBMITTED");
        assertThat(count("SELECT count(*) FROM committee_minutes WHERE player_id = ?", second)).isEqualTo(0L);

        Map<String, Object> withException = assignRequest("ACTA-F2", file, categoryId, panel);
        withException.put("capacityExceptionReason", "  Hermano de un jugador actual  ");
        postJson("/players/" + second + "/assignments", withException, committee)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacityExceptionReason").value("Hermano de un jugador actual"));
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_events WHERE action = 'PLAYER_CATEGORY_ASSIGNED' AND entity_id = ?",
                String.class, second)).isEqualTo("Hermano de un jugador actual");
        assertThat(countAudit("PLAYER_CATEGORY_ASSIGNED", committeeId) >= 2L).isTrue();
    }

    @Test
    void concurrentDecisionsNeverOverbookACategory() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID file = storedFile();
        String categoryId = createCategory(1);
        Panel panel = panel();
        UUID first = newPlayer();
        UUID second = newPlayer();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (UUID player : List.of(first, second)) {
                Callable<Integer> call = () -> {
                    start.await();
                    return postJson("/players/" + player + "/assignments",
                            assignRequest("ACTA-CC", file, categoryId, panel), committee).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1L);
            assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM category_assignments WHERE category_id = ?::uuid AND valid_to IS NULL", categoryId)).isEqualTo(1L);
    }

    // ---------- volver a decidir ----------

    @Test
    void aNewDecisionClosesTheOldCategoryAndPanelWithoutCountingThemAgainstTheCapacity() throws Exception {
        String committee = staffToken("COMMITTEE");
        String categoryId = createCategory(1);
        UUID player = newPlayer();
        UUID oldMinute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES ('ACTA-OLD', DATE '2026-01-10', ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, player, storedFile());
        Panel oldPanel = panel();
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?::uuid, ?, 'COMMITTEE', ?)",
                player, categoryId, oldPanel.coach(), oldMinute);
        for (UUID evaluator : oldPanel.evaluators()) {
            jdbc.update("INSERT INTO evaluator_assignments (player_id, evaluator_id, minute_id) VALUES (?, ?, ?)", player, evaluator, oldMinute);
        }

        postJson("/players/" + player + "/assignments", assignRequest("ACTA-NEW", storedFile(), categoryId, panel()), committee)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacityExceptionReason").doesNotExist());

        assertThat(count("SELECT count(*) FROM category_assignments WHERE player_id = ?", player)).isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM category_assignments WHERE player_id = ? AND valid_to IS NULL", player)).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM evaluator_assignments WHERE player_id = ?", player)).isEqualTo(6L);
        assertThat(count("SELECT count(*) FROM evaluator_assignments WHERE player_id = ? AND valid_to IS NULL", player)).isEqualTo(3L);
    }

    // ---------- rechazar ----------

    @Test
    void rejectingRequiresReasonAndMinuteAndRecordsThemWithAudit() throws Exception {
        String committee = staffToken("COMMITTEE");
        UUID committeeId = userIdOf(committee);
        UUID player = newPlayer();
        UUID file = storedFile();

        postJson("/players/" + player + "/rejections", Map.of("minuteNumber", " ", "reason", " "), committee)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'minuteNumber')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'attendees')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'minuteFileId')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'reason')]").isNotEmpty());
        assertThat(playerStatus(player)).isEqualTo("SUBMITTED");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("minuteNumber", "ACTA-20");
        request.put("meetingDate", LocalDate.now().minusDays(1).toString());
        request.put("attendees", List.of("Ana Gómez"));
        request.put("minuteFileId", file);
        request.put("reason", "  No cumple el perfil técnico  ");
        postJson("/players/" + player + "/rejections", request, committee)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerStatus").value("REJECTED"))
                .andExpect(jsonPath("$.minuteNumber").value("ACTA-20"));

        assertThat(playerStatus(player)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT decision_reason FROM committee_minutes WHERE player_id = ? AND decision = 'REJECTED'",
                String.class, player)).isEqualTo("No cumple el perfil técnico");
        assertThat(countAudit("PLAYER_ENROLLMENT_REJECTED", committeeId)).isEqualTo(1L);
        // Un jugador rechazado puede volver a radicar (RN-20) y entonces el comité decide de nuevo.
        assertThat(count("SELECT count(*) FROM category_assignments WHERE player_id = ?", player)).isEqualTo(0L);
    }
}
