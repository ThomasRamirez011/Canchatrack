package co.edu.unipamplona.canchatrack.players;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
 * Administración de temporadas y convocatorias contra PostgreSQL real, incluido el enlace con la radicación: una
 * convocatoria publicada recibe inscripciones, una cancelada o cerrada no, y la habilitación excepcional (RF-044)
 * concedida por la API es la que la radicación reconoce. No es transaccional a propósito.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CallsAdminIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";

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

    private ResultActions patchJson(String path, Object body, String bearer) throws Exception {
        return send(patch(API + path), body, bearer);
    }

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String login(String email) throws Exception {
        return body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isOk())).get("accessToken").asString();
    }

    private String staffToken(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Personal de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return login(email);
    }

    private UUID userIdOf(String token) throws Exception {
        return UUID.fromString(body(getJson("/auth/me", token).andExpect(status().isOk())).get("id").asString());
    }

    private record Guardian(String token, UUID userId, UUID guardianId) {
    }

    private Guardian registerGuardian() throws Exception {
        String email = "g-" + UUID.randomUUID() + "@club.test";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("email", email);
        request.put("fullName", "Ana Acudiente");
        request.put("password", PASSWORD);
        request.put("documentType", "CC");
        request.put("documentNumber", String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
        UUID userId = UUID.fromString(body(postJson("/guardians", request, null).andExpect(status().isCreated())).get("id").asString());
        UUID guardianId = jdbc.queryForObject("SELECT id FROM guardians WHERE user_id = ?", UUID.class, userId);
        return new Guardian(login(email), userId, guardianId);
    }

    private Map<String, Object> enrollmentRequest(String callId) {
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("firstName", "Juan");
        player.put("lastName", "Pérez");
        player.put("documentType", "TI");
        player.put("documentNumber", "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        player.put("birthDate", "2014-03-09");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("callId", callId);
        request.put("player", player);
        request.put("relationship", "Madre");
        request.put("acceptDataProcessing", true);
        request.put("acceptParticipation", true);
        return request;
    }

    private static String in(Duration offset) {
        return Instant.now().plus(offset).toString();
    }

    private String createSeason(String coordinator) throws Exception {
        Map<String, Object> request = Map.of("name", "Temporada " + UUID.randomUUID(),
                "startAt", in(Duration.ofDays(-30)), "endAt", in(Duration.ofDays(300)));
        return body(postJson("/seasons", request, coordinator).andExpect(status().isCreated())).get("id").asString();
    }

    private Map<String, Object> callRequest(String seasonId, Duration start, Duration end) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("seasonId", seasonId);
        request.put("name", "Convocatoria " + UUID.randomUUID());
        request.put("startAt", in(start));
        request.put("endAt", in(end));
        request.put("instructions", "Traer documento de identidad");
        return request;
    }

    private String createDraft(String coordinator, String seasonId, Duration start, Duration end) throws Exception {
        return body(postJson("/calls", callRequest(seasonId, start, end), coordinator).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))).get("id").asString();
    }

    private String createPublished(String coordinator, String seasonId) throws Exception {
        String callId = createDraft(coordinator, seasonId, Duration.ofDays(-1), Duration.ofDays(1));
        postJson("/calls/" + callId + "/publish", null, coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        return callId;
    }

    private long countAudit(String action, UUID actor, String reason) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = ? AND actor_id = ? AND (?::text IS NULL OR reason = ?)",
                Long.class, action, actor, reason, reason);
        return total == null ? 0 : total;
    }

    // ---------- autorización ----------

    @Test
    void onlyTheCoordinatorAdministersSeasonsAndCalls() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        String seasonId = createSeason(coordinator);
        String callId = createDraft(coordinator, seasonId, Duration.ofDays(-1), Duration.ofDays(1));
        Map<String, Object> season = Map.of("name", "X", "startAt", in(Duration.ZERO), "endAt", in(Duration.ofDays(1)));

        postJson("/seasons", season, null).andExpect(status().isUnauthorized());
        postJson("/calls", callRequest(seasonId, Duration.ZERO, Duration.ofDays(1)), null).andExpect(status().isUnauthorized());

        for (String token : new String[] {registerGuardian().token(), staffToken("COACH"), staffToken("COMMITTEE")}) {
            postJson("/seasons", season, token).andExpect(status().isForbidden());
            getJson("/seasons", token).andExpect(status().isForbidden());
            postJson("/seasons/" + seasonId + "/activate", null, token).andExpect(status().isForbidden());
            postJson("/calls", callRequest(seasonId, Duration.ZERO, Duration.ofDays(1)), token).andExpect(status().isForbidden());
            getJson("/calls", token).andExpect(status().isForbidden());
            getJson("/calls/" + callId, token).andExpect(status().isForbidden());
            patchJson("/calls/" + callId, Map.of("instructions", "x"), token).andExpect(status().isForbidden());
            postJson("/calls/" + callId + "/publish", null, token).andExpect(status().isForbidden());
            postJson("/calls/" + callId + "/cancel", Map.of("reason", "x"), token).andExpect(status().isForbidden());
            postJson("/calls/" + callId + "/exceptions", Map.of("guardianId", UUID.randomUUID(), "reason", "x",
                    "expiresAt", in(Duration.ofDays(1))), token).andExpect(status().isForbidden());
            getJson("/calls/" + callId + "/exceptions", token).andExpect(status().isForbidden());
            getJson("/calls/open", token).andExpect(status().isOk()); // la consulta de abiertas sigue siendo de todos
        }
        assertThat(jdbc.queryForObject("SELECT status FROM calls WHERE id = ?::uuid", String.class, callId)).isEqualTo("DRAFT");
    }

    // ---------- temporadas ----------

    @Test
    void seasonsAreCreatedPlannedAndActivatedOnce() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID coordinatorId = userIdOf(coordinator);
        Map<String, Object> request = Map.of("name", "Temporada " + UUID.randomUUID(),
                "startAt", in(Duration.ofDays(1)), "endAt", in(Duration.ofDays(300)));

        String seasonId = body(postJson("/seasons", request, coordinator)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLANNED"))).get("id").asString();
        postJson("/seasons", request, coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());

        postJson("/seasons/" + seasonId + "/activate", null, coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        postJson("/seasons/" + seasonId + "/activate", null, coordinator).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"));
        postJson("/seasons/" + UUID.randomUUID() + "/activate", null, coordinator).andExpect(status().isNotFound());

        assertThat(body(getJson("/seasons", coordinator).andExpect(status().isOk())).findValuesAsString("id")).contains(seasonId);
        assertThat(countAudit("SEASON_CREATED", coordinatorId, null)).isEqualTo(1L);
        assertThat(countAudit("SEASON_ACTIVATED", coordinatorId, null)).isEqualTo(1L);
    }

    // ---------- ciclo de la convocatoria ----------

    @Test
    void aDraftIsInvisibleToGuardiansUntilItIsPublished() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID coordinatorId = userIdOf(coordinator);
        Guardian guardian = registerGuardian();
        String callId = createDraft(coordinator, createSeason(coordinator), Duration.ofDays(-1), Duration.ofDays(1));

        assertThat(body(getJson("/calls/open", guardian.token()).andExpect(status().isOk())).findValuesAsString("id"))
                .doesNotContain(callId);
        postJson("/enrollments", enrollmentRequest(callId), guardian.token()).andExpect(status().isNotFound());

        postJson("/calls/" + callId + "/publish", null, coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.windowOpen").value(true));

        assertThat(body(getJson("/calls/open", guardian.token()).andExpect(status().isOk())).findValuesAsString("id"))
                .contains(callId);
        postJson("/enrollments", enrollmentRequest(callId), guardian.token()).andExpect(status().isCreated());
        postJson("/calls/" + callId + "/publish", null, coordinator).andExpect(status().isConflict());
        assertThat(countAudit("CALL_CREATED", coordinatorId, null)).isEqualTo(1L);
        assertThat(countAudit("CALL_PUBLISHED", coordinatorId, null)).isEqualTo(1L);
    }

    @Test
    void aCallWhoseClosingAlreadyPassedCannotBePublished() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        String callId = createDraft(coordinator, createSeason(coordinator), Duration.ofDays(-3), Duration.ofDays(-1));

        postJson("/calls/" + callId + "/publish", null, coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'endAt')]").isNotEmpty());

        getJson("/calls/" + callId, coordinator).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void aPublishedCallOnlyMovesItsClosingWithAReasonAndKeepsItsNameAndOpening() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID coordinatorId = userIdOf(coordinator);
        String callId = createPublished(coordinator, createSeason(coordinator));
        String newEnd = in(Duration.ofDays(5));

        patchJson("/calls/" + callId, Map.of("name", "Otro nombre"), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());
        patchJson("/calls/" + callId, Map.of("endAt", newEnd), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'reason')]").isNotEmpty());
        patchJson("/calls/" + callId, Map.of("endAt", newEnd, "reason", "Ampliación solicitada por el comité"), coordinator)
                .andExpect(status().isOk());
        patchJson("/calls/" + callId, Map.of("instructions", "Nuevas instrucciones"), coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.instructions").value("Nuevas instrucciones"));

        assertThat(countAudit("CALL_UPDATED", coordinatorId, "Ampliación solicitada por el comité")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT before_state ->> 'endAt' IS NOT NULL AND after_state ->> 'endAt' IS NOT NULL
                FROM audit_events WHERE action = 'CALL_UPDATED' AND reason = 'Ampliación solicitada por el comité'
                AND entity_id = ?::uuid
                """, Boolean.class, callId)).isTrue();
    }

    @Test
    void cancellingClosesReceptionButKeepsTheEnrollmentsAlreadyFiled() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID coordinatorId = userIdOf(coordinator);
        String callId = createPublished(coordinator, createSeason(coordinator));
        Guardian first = registerGuardian();
        Guardian second = registerGuardian();
        postJson("/enrollments", enrollmentRequest(callId), first.token()).andExpect(status().isCreated());

        postJson("/calls/" + callId + "/cancel", Map.of("reason", " "), coordinator).andExpect(status().isUnprocessableEntity());
        postJson("/calls/" + callId + "/cancel", Map.of("reason", "Cupos agotados"), coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        postJson("/enrollments", enrollmentRequest(callId), second.token()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENROLLMENT_CLOSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE call_id = ?::uuid", Long.class, callId)).isEqualTo(1L);
        postJson("/calls/" + callId + "/cancel", Map.of("reason", "Otra vez"), coordinator).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"));
        patchJson("/calls/" + callId, Map.of("instructions", "x"), coordinator).andExpect(status().isConflict());
        assertThat(countAudit("CALL_CANCELLED", coordinatorId, "Cupos agotados")).isEqualTo(1L);
    }

    // ---------- habilitaciones excepcionales (RF-044) ----------

    @Test
    void anExceptionGrantedThroughTheApiLetsOnlyThatGuardianEnrollAfterTheEarlyClosing() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID coordinatorId = userIdOf(coordinator);
        String callId = createPublished(coordinator, createSeason(coordinator));
        Guardian enabled = registerGuardian();
        Guardian other = registerGuardian();

        patchJson("/calls/" + callId, Map.of("endAt", in(Duration.ofHours(-1)), "reason", "Cierre anticipado por cupo"),
                coordinator).andExpect(status().isOk()).andExpect(jsonPath("$.windowOpen").value(false));
        postJson("/enrollments", enrollmentRequest(callId), enabled.token()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENROLLMENT_CLOSED"));

        Map<String, Object> grant = Map.of("guardianId", enabled.guardianId(), "reason", "Falla del portal el último día",
                "expiresAt", in(Duration.ofDays(2)));
        postJson("/calls/" + callId + "/exceptions", grant, coordinator).andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.guardianId").value(enabled.guardianId().toString()));

        postJson("/enrollments", enrollmentRequest(callId), enabled.token()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.viaException").value(true));
        postJson("/enrollments", enrollmentRequest(callId), other.token()).andExpect(status().isConflict());

        getJson("/calls/" + callId + "/exceptions", coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].grantedBy").value(coordinatorId.toString()));
        assertThat(countAudit("CALL_EXCEPTION_GRANTED", coordinatorId, "Falla del portal el último día")).isEqualTo(1L);
    }

    @Test
    void anExceptionNeedsAValidGuardianAReasonAndABoundedFutureExpiry() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        String callId = createPublished(coordinator, createSeason(coordinator));
        Guardian guardian = registerGuardian();

        postJson("/calls/" + callId + "/exceptions", Map.of("guardianId", UUID.randomUUID(), "reason", "Motivo",
                "expiresAt", in(Duration.ofDays(1))), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'guardianId')]").isNotEmpty());
        postJson("/calls/" + callId + "/exceptions", Map.of("guardianId", guardian.guardianId(), "reason", "Motivo",
                "expiresAt", in(Duration.ofMinutes(-5))), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'expiresAt')]").isNotEmpty());
        postJson("/calls/" + callId + "/exceptions", Map.of("guardianId", guardian.guardianId(), "reason", "Motivo",
                "expiresAt", in(Duration.ofDays(45))), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'expiresAt')]").isNotEmpty());
        postJson("/calls/" + callId + "/exceptions", Map.of("guardianId", guardian.guardianId(), "reason", " ",
                "expiresAt", in(Duration.ofDays(1))), coordinator).andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM call_exceptions WHERE call_id = ?::uuid", Long.class, callId)).isZero();
    }

    @Test
    void exceptionsOnlyApplyToPublishedCalls() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        Guardian guardian = registerGuardian();
        String draft = createDraft(coordinator, createSeason(coordinator), Duration.ofDays(-1), Duration.ofDays(1));

        postJson("/calls/" + draft + "/exceptions", Map.of("guardianId", guardian.guardianId(), "reason", "Motivo",
                "expiresAt", in(Duration.ofDays(1))), coordinator).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"));
    }

    // ---------- validación y recursos inexistentes ----------

    @Test
    void invalidCallsReportEveryFieldAndUnknownIdsAreNotFound() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("seasonId", UUID.randomUUID());
        bad.put("name", "Convocatoria");
        bad.put("startAt", in(Duration.ofDays(2)));
        bad.put("endAt", in(Duration.ofDays(1)));

        postJson("/calls", bad, coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'seasonId')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'endAt')]").isNotEmpty());
        postJson("/calls", Map.of("seasonId", UUID.randomUUID()), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());

        UUID unknown = UUID.randomUUID();
        getJson("/calls/" + unknown, coordinator).andExpect(status().isNotFound());
        getJson("/calls/" + unknown + "/exceptions", coordinator).andExpect(status().isNotFound());
        postJson("/calls/" + unknown + "/publish", null, coordinator).andExpect(status().isNotFound());
        postJson("/calls/" + unknown + "/cancel", Map.of("reason", "x"), coordinator).andExpect(status().isNotFound());
    }

    @Test
    void theListFiltersBySeasonAndStatus() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        String seasonId = createSeason(coordinator);
        String published = createPublished(coordinator, seasonId);
        String draft = createDraft(coordinator, seasonId, Duration.ofDays(-1), Duration.ofDays(1));

        assertThat(body(getJson("/calls?seasonId=" + seasonId, coordinator).andExpect(status().isOk())).findValuesAsString("id"))
                .containsExactlyInAnyOrder(published, draft);
        assertThat(body(getJson("/calls?seasonId=" + seasonId + "&status=PUBLISHED", coordinator).andExpect(status().isOk()))
                .findValuesAsString("id")).containsExactly(published);
    }
}
