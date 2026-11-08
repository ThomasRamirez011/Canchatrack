package co.edu.unipamplona.canchatrack.players;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Autorregistro de acudientes, convocatorias abiertas, radicación de inscripciones y consulta de jugadores contra
 * PostgreSQL real. No es transaccional a propósito: se verifica lo que cada caso de uso confirma (o revierte).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EnrollmentIT {

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

    private record Guardian(String email, String token, UUID userId, UUID guardianId) {
    }

    private ResultActions postJson(String path, Object body, String bearer) throws Exception {
        var request = post(API + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
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

    private String login(String email) throws Exception {
        return body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private static String randomDigits() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    /** Registra un acudiente por la API pública (como lo haría un cliente real) y abre sesión. */
    private Guardian registerGuardian() throws Exception {
        String email = "g-" + UUID.randomUUID() + "@club.test";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("email", email);
        request.put("fullName", "Ana Acudiente");
        request.put("password", PASSWORD);
        request.put("documentType", "CC");
        request.put("documentNumber", randomDigits());
        request.put("phone", "300 123 4567");
        UUID userId = UUID.fromString(body(postJson("/guardians", request, null).andExpect(status().isCreated())).get("id").asText());
        UUID guardianId = jdbc.queryForObject("SELECT id FROM guardians WHERE user_id = ?", UUID.class, userId);
        return new Guardian(email, login(email), userId, guardianId);
    }

    private String staffToken(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Personal de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return login(email);
    }

    private UUID newCall(String status, String startsIn, String endsIn) {
        UUID season = jdbc.queryForObject(
                "INSERT INTO seasons (name, start_at, end_at) VALUES (?, now() - interval '30 days', now() + interval '1 year') RETURNING id",
                UUID.class, "T-" + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO calls (season_id, name, start_at, end_at, status)
                VALUES (?, ?, now() + ?::interval, now() + ?::interval, ?) RETURNING id
                """, UUID.class, season, "Convocatoria " + UUID.randomUUID(), startsIn, endsIn, status);
    }

    private UUID openCall() {
        return newCall("PUBLISHED", "-1 day", "1 day");
    }

    private Map<String, Object> enrollmentRequest(UUID callId, String documentNumber) {
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("firstName", "Juan");
        player.put("lastName", "Pérez");
        player.put("documentType", "TI");
        player.put("documentNumber", documentNumber);
        player.put("birthDate", "2014-03-09");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("callId", callId);
        request.put("player", player);
        request.put("relationship", "Madre");
        request.put("acceptDataProcessing", true);
        request.put("acceptParticipation", true);
        return request;
    }

    private static String newPlayerDocument() {
        return "TI-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long countAudit(String action, UUID actor, String reason) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = ? AND actor_id = ? AND (?::text IS NULL OR reason = ?)",
                Long.class, action, actor, reason, reason);
        return total == null ? 0 : total;
    }

    // ---------- registro de acudientes ----------

    @Test
    void aGuardianRegistersPubliclyAndCanLogIn() throws Exception {
        String email = "reg-" + UUID.randomUUID() + "@club.test";
        Map<String, Object> request = Map.of("email", email, "fullName", "Ana Acudiente", "password", PASSWORD,
                "documentType", "CC", "documentNumber", randomDigits());

        postJson("/guardians", request, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("GUARDIAN"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String token = login(email);
        getJson("/auth/me", token).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("GUARDIAN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'GUARDIAN_REGISTERED' AND actor_id = "
                + "(SELECT id FROM users WHERE lower(email) = lower(?))", Long.class, email)).isEqualTo(1L);
    }

    @Test
    void registrationReportsEveryInvalidFieldAndRejectsDuplicates() throws Exception {
        postJson("/guardians", Map.of("email", "no-es-correo", "fullName", "Ana", "password", "debil1",
                "documentType", "TI", "documentNumber", "ab"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'documentType')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'documentNumber')]").isNotEmpty());

        Guardian existing = registerGuardian();
        String documentOfExisting = jdbc.queryForObject("SELECT document_number FROM guardians WHERE id = ?", String.class,
                existing.guardianId());
        postJson("/guardians", Map.of("email", existing.email().toUpperCase(), "fullName", "Otra", "password", PASSWORD,
                "documentType", "CC", "documentNumber", randomDigits()), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").isNotEmpty());
        postJson("/guardians", Map.of("email", "otro-" + UUID.randomUUID() + "@club.test", "fullName", "Otra",
                "password", PASSWORD, "documentType", "CC", "documentNumber", documentOfExisting), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'documentNumber')]").isNotEmpty());
    }

    // ---------- convocatorias ----------

    @Test
    void openCallsRequireAuthenticationAndListOnlyPublishedOpenOnes() throws Exception {
        UUID open = openCall();
        UUID closed = newCall("PUBLISHED", "-3 days", "-1 day");
        UUID upcoming = newCall("PUBLISHED", "1 day", "3 days");
        UUID draft = newCall("DRAFT", "-1 day", "1 day");
        UUID cancelled = newCall("CANCELLED", "-1 day", "1 day");

        getJson("/calls/open", null).andExpect(status().isUnauthorized());

        String ids = body(getJson("/calls/open", staffToken("COACH")).andExpect(status().isOk())).findValuesAsText("id").toString();
        assertThat(ids).contains(open.toString())
                .doesNotContain(closed.toString(), upcoming.toString(), draft.toString(), cancelled.toString());
    }

    // ---------- radicación ----------

    @Test
    void aGuardianFilesAnEnrollmentWithPlayerLinkConsentsAndAudit() throws Exception {
        Guardian guardian = registerGuardian();
        UUID call = openCall();
        String document = newPlayerDocument();

        JsonNode receipt = body(postJson("/enrollments", enrollmentRequest(call, document), guardian.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filingCode").value(org.hamcrest.Matchers.startsWith("RAD-")))
                .andExpect(jsonPath("$.playerCode").value(org.hamcrest.Matchers.startsWith("JG-")))
                .andExpect(jsonPath("$.playerStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.viaException").value(false)));

        UUID playerId = UUID.fromString(receipt.get("playerId").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, playerId)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT is_primary FROM player_guardians WHERE player_id = ? AND guardian_id = ?",
                Boolean.class, playerId, guardian.guardianId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE player_id = ? AND call_id = ?",
                Long.class, playerId, call)).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT kind FROM consents WHERE player_id = ? AND guardian_id = ? AND method = 'AUTHENTICATED_ACCEPTANCE' ORDER BY kind",
                String.class, playerId, guardian.guardianId())).containsExactly("DATA_PROCESSING", "PARTICIPATION");
        assertThat(countAudit("ENROLLMENT_SUBMITTED", guardian.userId(), null)).isEqualTo(1L);
    }

    @Test
    void outsideTheCallWindowItIsRejectedButTheAttemptLeavesTrace() throws Exception {
        Guardian guardian = registerGuardian();
        UUID closed = newCall("PUBLISHED", "-3 days", "-1 day");
        String document = newPlayerDocument();

        postJson("/enrollments", enrollmentRequest(closed, document), guardian.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENROLLMENT_CLOSED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM players WHERE document_number = ?", Long.class, document)).isZero();
        assertThat(countAudit("ENROLLMENT_REJECTED", guardian.userId(), "CALL_CLOSED")).isEqualTo(1L);
    }

    @Test
    void anExceptionalEnablementOpensAClosedCallForThatGuardianOnly() throws Exception {
        Guardian enabled = registerGuardian();
        Guardian other = registerGuardian();
        UUID coordinator = jdbc.queryForObject("""
                INSERT INTO users (email, full_name, password_hash) VALUES (?, 'Coordinador', ?) RETURNING id
                """, UUID.class, "coord-" + UUID.randomUUID() + "@club.test", encoder.encode(PASSWORD));
        UUID closed = newCall("PUBLISHED", "-3 days", "-1 day");
        jdbc.update("""
                INSERT INTO call_exceptions (call_id, guardian_id, reason, granted_by, expires_at)
                VALUES (?, ?, 'Caso justificado por el comité', ?, now() + interval '2 days')
                """, closed, enabled.guardianId(), coordinator);

        postJson("/enrollments", enrollmentRequest(closed, newPlayerDocument()), enabled.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viaException").value(true));
        postJson("/enrollments", enrollmentRequest(closed, newPlayerDocument()), other.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENROLLMENT_CLOSED"));
    }

    @Test
    void anExpiredExceptionDoesNotCount() throws Exception {
        Guardian guardian = registerGuardian();
        UUID coordinator = jdbc.queryForObject("""
                INSERT INTO users (email, full_name, password_hash) VALUES (?, 'Coordinador', ?) RETURNING id
                """, UUID.class, "coord-" + UUID.randomUUID() + "@club.test", encoder.encode(PASSWORD));
        UUID closed = newCall("PUBLISHED", "-3 days", "-1 day");
        jdbc.update("""
                INSERT INTO call_exceptions (call_id, guardian_id, reason, granted_by, granted_at, expires_at)
                VALUES (?, ?, 'Vencida', ?, now() - interval '3 days', now() - interval '1 day')
                """, closed, guardian.guardianId(), coordinator);

        postJson("/enrollments", enrollmentRequest(closed, newPlayerDocument()), guardian.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENROLLMENT_CLOSED"));
    }

    @Test
    void consentsAreMandatoryAndNothingIsPersistedWithoutThem() throws Exception {
        Guardian guardian = registerGuardian();
        UUID call = openCall();
        String document = newPlayerDocument();
        Map<String, Object> request = enrollmentRequest(call, document);
        request.put("acceptDataProcessing", false);
        request.put("acceptParticipation", false);

        postJson("/enrollments", request, guardian.token())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'acceptDataProcessing')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'acceptParticipation')]").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM players WHERE document_number = ?", Long.class, document)).isZero();
    }

    @Test
    void shapeErrorsAreReportedFieldByField() throws Exception {
        Guardian guardian = registerGuardian();

        postJson("/enrollments", Map.of("callId", UUID.randomUUID(),
                "player", Map.of("firstName", "", "lastName", "", "documentType", "", "documentNumber", "", "birthDate", "2014-03-09"),
                "acceptDataProcessing", true, "acceptParticipation", true), guardian.token())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'player.firstName')]").isNotEmpty());
    }

    @Test
    void onlyGuardiansCanFileAndAnonymousCallsAreRejected() throws Exception {
        UUID call = openCall();

        postJson("/enrollments", enrollmentRequest(call, newPlayerDocument()), null).andExpect(status().isUnauthorized());
        postJson("/enrollments", enrollmentRequest(call, newPlayerDocument()), staffToken("COORDINATOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void duplicateFilingsAreGenericConflictsThatRevealNothingAboutOtherFamilies() throws Exception {
        Guardian owner = registerGuardian();
        Guardian stranger = registerGuardian();
        UUID call = openCall();
        UUID otherCall = openCall();
        String document = newPlayerDocument();
        postJson("/enrollments", enrollmentRequest(call, document), owner.token()).andExpect(status().isCreated());

        // el mismo acudiente, mismo jugador con proceso en curso
        String sameGuardian = body(postJson("/enrollments", enrollmentRequest(otherCall, document), owner.token())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAYER_STATE_CONFLICT")))
                .get("detail").asText();
        // otro acudiente con el mismo documento: misma respuesta, sin pistas de que el jugador existe
        String otherGuardian = body(postJson("/enrollments", enrollmentRequest(otherCall, document), stranger.token())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAYER_STATE_CONFLICT")))
                .get("detail").asText();

        assertThat(otherGuardian).isEqualTo(sameGuardian).doesNotContain(document);
        assertThat(countAudit("ENROLLMENT_REJECTED", stranger.userId(), "PLAYER_NOT_LINKED")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments e JOIN players p ON p.id = e.player_id WHERE p.document_number = ?",
                Long.class, document)).isEqualTo(1L);
    }

    @Test
    void aRejectedPlayerCanBeFiledAgainInANewCallReusingTheRecord() throws Exception {
        Guardian guardian = registerGuardian();
        String document = newPlayerDocument();
        JsonNode first = body(postJson("/enrollments", enrollmentRequest(openCall(), document), guardian.token())
                .andExpect(status().isCreated()));
        UUID playerId = UUID.fromString(first.get("playerId").asText());
        jdbc.update("UPDATE players SET status = 'REJECTED' WHERE id = ?", playerId);

        JsonNode second = body(postJson("/enrollments", enrollmentRequest(openCall(), document), guardian.token())
                .andExpect(status().isCreated()));

        assertThat(second.get("playerId").asText()).isEqualTo(playerId.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, playerId)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE player_id = ?", Long.class, playerId)).isEqualTo(2L);
    }

    // ---------- consulta de jugadores ----------

    @Test
    void playersAreVisibleOnlyToTheirGuardianAndToCoordinatorAndCommittee() throws Exception {
        Guardian owner = registerGuardian();
        Guardian stranger = registerGuardian();
        JsonNode receipt = body(postJson("/enrollments", enrollmentRequest(openCall(), newPlayerDocument()), owner.token())
                .andExpect(status().isCreated()));
        String playerId = receipt.get("playerId").asText();

        getJson("/players/" + playerId, owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(playerId))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.enrollments[0].filingCode").value(receipt.get("filingCode").asText()));
        getJson("/players/" + playerId, staffToken("COORDINATOR")).andExpect(status().isOk());
        getJson("/players/" + playerId, staffToken("COMMITTEE")).andExpect(status().isOk());

        // sin acceso y sin existencia responden igual
        getJson("/players/" + playerId, stranger.token()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        getJson("/players/" + playerId, staffToken("COACH")).andExpect(status().isNotFound());
        getJson("/players/" + UUID.randomUUID(), owner.token()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        getJson("/players/" + playerId, null).andExpect(status().isUnauthorized());
    }

    @Test
    void listingIsScopedByRoleAndFilteredByStatus() throws Exception {
        Guardian owner = registerGuardian();
        Guardian stranger = registerGuardian();
        String mine = body(postJson("/enrollments", enrollmentRequest(openCall(), newPlayerDocument()), owner.token())
                .andExpect(status().isCreated())).get("playerId").asText();
        String theirs = body(postJson("/enrollments", enrollmentRequest(openCall(), newPlayerDocument()), stranger.token())
                .andExpect(status().isCreated())).get("playerId").asText();

        List<String> ownerSees = body(getJson("/players", owner.token()).andExpect(status().isOk())).get("items")
                .findValuesAsText("id");
        assertThat(ownerSees).containsExactly(mine);

        JsonNode coordinatorView = body(getJson("/players?size=100", staffToken("COORDINATOR")).andExpect(status().isOk()));
        assertThat(coordinatorView.get("items").findValuesAsText("id")).contains(mine, theirs);
        assertThat(coordinatorView.get("total").asLong()).isGreaterThanOrEqualTo(2L);

        List<String> active = body(getJson("/players?status=ACTIVE&size=100", staffToken("COMMITTEE")).andExpect(status().isOk()))
                .get("items").findValuesAsText("id");
        assertThat(active).doesNotContain(mine, theirs);

        getJson("/players", staffToken("COACH")).andExpect(status().isForbidden());
        getJson("/players?status=NOPE", staffToken("COORDINATOR")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        getJson("/players", null).andExpect(status().isUnauthorized());
    }
}
