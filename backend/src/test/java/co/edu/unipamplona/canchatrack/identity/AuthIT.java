package co.edu.unipamplona.canchatrack.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;

/**
 * Flujo completo de autenticación contra PostgreSQL real: login, renovación con rotación, cierre de sesión,
 * bloqueo por intentos, autorización por rol y vínculo acudiente-jugador. No es transaccional a propósito: los
 * casos de uso confirman sus propias transacciones (incluidos los rechazos), y eso es lo que se verifica.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthIT {

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
    @Autowired
    private PlayerAccessPolicy playerAccess;

    // ---------- utilidades ----------

    private String newAccount(String roleCode) {
        String email = "it-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject(
                "INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Cuenta de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return email;
    }

    private ResultActions postJson(String path, Object body, String bearer) throws Exception {
        var request = post(API + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private JsonNode login(String email, String password) throws Exception {
        String body = postJson("/auth/login", Map.of("email", email, "password", password), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get(API + "/auth/me").header("Authorization", "Bearer " + accessToken));
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", UUID.class, email);
    }

    // ---------- acceso sin credenciales ----------

    @Test
    void protectedEndpointWithoutTokenIs401ProblemJson() throws Exception {
        mvc.perform(get(API + "/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void garbageAndTamperedTokensAreRejected() throws Exception {
        String email = newAccount("COACH");
        String access = login(email, PASSWORD).get("accessToken").asText();
        String tampered = access.substring(0, access.length() - 2) + (access.endsWith("AA") ? "BB" : "AA");

        me("esto-no-es-un-jwt").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        me(tampered).andExpect(status().isUnauthorized());
    }

    @Test
    void healthCheckStaysPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---------- login ----------

    @Test
    void loginThenMeReturnsTheAccountWithItsRoles() throws Exception {
        String email = newAccount("COORDINATOR");

        JsonNode session = login(email, PASSWORD);

        assertThat(session.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(session.get("expiresIn").asLong()).isPositive();
        assertThat(session.get("refreshToken").asText()).isNotBlank();
        assertThat(session.get("user").get("roles").get(0).asText()).isEqualTo("COORDINATOR");
        assertThat(session.toString()).doesNotContain("password");
        me(session.get("accessToken").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("COORDINATOR"));
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        String email = newAccount("COACH");

        String wrongPassword = postJson("/auth/login", Map.of("email", email, "password", "otra-clave-1234"), null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownEmail = postJson("/auth/login", Map.of("email", "nadie@club.test", "password", "otra-clave-1234"), null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(wrongPassword).get("detail")).isEqualTo(json.readTree(unknownEmail).get("detail"));
        assertThat(json.readTree(wrongPassword).get("code").asText()).isEqualTo("AUTH_UNAUTHENTICATED");
    }

    @Test
    void fiveFailuresLockTheAccountEvenAgainstTheRightPassword() throws Exception {
        String email = newAccount("COACH");

        for (int i = 0; i < 5; i++) {
            postJson("/auth/login", Map.of("email", email, "password", "otra-clave-1234"), null)
                    .andExpect(status().isUnauthorized());
        }

        postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null).andExpect(status().isUnauthorized());
        Boolean locked = jdbc.queryForObject("SELECT locked_until > now() FROM users WHERE id = ?", Boolean.class, userId(email));
        assertThat(locked).isTrue();
    }

    @Test
    void failedAndSuccessfulLoginsAreAudited() throws Exception {
        String email = newAccount("COACH");
        UUID id = userId(email);

        postJson("/auth/login", Map.of("email", email, "password", "otra-clave-1234"), null).andExpect(status().isUnauthorized());
        login(email, PASSWORD);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE actor_id = ? AND action = 'AUTH_LOGIN_FAILED' AND outcome = 'DENIED'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE actor_id = ? AND action = 'AUTH_LOGIN_SUCCESS'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void invalidLoginBodiesAreValidationErrors() throws Exception {
        postJson("/auth/login", Map.of("email", "", "password", ""), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ---------- refresh y logout ----------

    @Test
    void refreshRotatesAndReusingTheOldTokenKillsTheSession() throws Exception {
        String email = newAccount("COACH");
        JsonNode first = login(email, PASSWORD);
        String oldRefresh = first.get("refreshToken").asText();

        JsonNode second = json.readTree(postJson("/auth/refresh", Map.of("refreshToken", oldRefresh), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String newRefresh = second.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        me(second.get("accessToken").asText()).andExpect(status().isOk());

        postJson("/auth/refresh", Map.of("refreshToken", oldRefresh), null).andExpect(status().isUnauthorized());
        postJson("/auth/refresh", Map.of("refreshToken", newRefresh), null)
                .andExpect(status().isUnauthorized()); // la reutilización revocó la familia entera
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE actor_id = ? AND action = 'AUTH_REFRESH_REUSE_DETECTED'",
                Integer.class, userId(email))).isEqualTo(1);
    }

    @Test
    void refreshTokensAreStoredOnlyAsHashes() throws Exception {
        String email = newAccount("COACH");
        String refresh = login(email, PASSWORD).get("refreshToken").asText();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, refresh)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId(email))).isEqualTo(1);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String email = newAccount("COACH");
        String refresh = login(email, PASSWORD).get("refreshToken").asText();

        postJson("/auth/logout", Map.of("refreshToken", refresh), null).andExpect(status().isNoContent());
        postJson("/auth/logout", Map.of("refreshToken", refresh), null).andExpect(status().isNoContent()); // idempotente
        postJson("/auth/refresh", Map.of("refreshToken", refresh), null).andExpect(status().isUnauthorized());
    }

    @Test
    void aDisabledAccountIsRejectedByMeAndCannotRenew() throws Exception {
        String email = newAccount("COACH");
        JsonNode session = login(email, PASSWORD);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", userId(email));

        me(session.get("accessToken").asText()).andExpect(status().isUnauthorized());
        postJson("/auth/refresh", Map.of("refreshToken", session.get("refreshToken").asText()), null)
                .andExpect(status().isUnauthorized());
    }

    // ---------- alta de usuarios y autorización por rol ----------

    @Test
    void coordinatorCreatesStaffAccountsWhoCanThenLogIn() throws Exception {
        String coordinator = newAccount("COORDINATOR");
        String token = login(coordinator, PASSWORD).get("accessToken").asText();
        String newEmail = "nuevo-" + UUID.randomUUID() + "@club.test";

        postJson("/users", Map.of("email", newEmail, "fullName", "Nuevo Evaluador", "password", "Evaluador-2026",
                "roles", List.of("EVALUATOR")), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.roles[0]").value("EVALUATOR"))
                .andExpect(jsonPath("$.password").doesNotExist());

        assertThat(login(newEmail, "Evaluador-2026").get("user").get("roles").get(0).asText()).isEqualTo("EVALUATOR");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE action = 'USER_CREATED' AND actor_id = ?", Integer.class, userId(coordinator)))
                .isEqualTo(1);
    }

    @Test
    void nonCoordinatorsCannotCreateAccounts() throws Exception {
        String token = login(newAccount("COACH"), PASSWORD).get("accessToken").asText();

        postJson("/users", Map.of("email", "x@club.test", "fullName", "X", "password", "Evaluador-2026",
                "roles", List.of("COACH")), token)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void creatingAccountsRequiresAuthentication() throws Exception {
        postJson("/users", Map.of("email", "x@club.test", "fullName", "X", "password", "Evaluador-2026",
                "roles", List.of("COACH")), null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidAccountDataIsReportedFieldByField() throws Exception {
        String token = login(newAccount("COORDINATOR"), PASSWORD).get("accessToken").asText();

        // Reglas de dominio: contraseña débil, rol de acudiente no permitido
        postJson("/users", Map.of("email", "ok-" + UUID.randomUUID() + "@club.test", "fullName", "X", "password", "deb1",
                "roles", List.of("GUARDIAN")), token)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'roles')]").isNotEmpty());

        // Reglas de forma (Bean Validation): campos obligatorios
        postJson("/users", Map.of("email", "", "fullName", "", "password", "", "roles", List.of()), token)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String token = login(newAccount("COORDINATOR"), PASSWORD).get("accessToken").asText();
        String existing = newAccount("COACH");

        postJson("/users", Map.of("email", existing.toUpperCase(), "fullName", "Dup", "password", "Evaluador-2026",
                "roles", List.of("COACH")), token)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").isNotEmpty());
    }

    // ---------- vínculo acudiente-jugador ----------

    @Test
    void guardianAccessFollowsTheActiveLinkToThePlayer() {
        String guardianEmail = newAccount("GUARDIAN");
        UUID guardianUser = userId(guardianEmail);
        UUID guardianRow = jdbc.queryForObject(
                "INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                UUID.class, guardianUser, "cc-" + UUID.randomUUID().toString().substring(0, 20));
        UUID linked = newPlayer();
        UUID unlinked = newPlayer();
        UUID formerlyLinked = newPlayer();
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, true)", linked, guardianRow);
        jdbc.update("""
                INSERT INTO player_guardians (player_id, guardian_id, valid_from, valid_to)
                VALUES (?, ?, now() - interval '10 days', now() - interval '1 day')
                """, formerlyLinked, guardianRow);

        assertThat(playerAccess.canAccess(guardianUser, java.util.Set.of("GUARDIAN"), linked)).isTrue();
        assertThat(playerAccess.canAccess(guardianUser, java.util.Set.of("GUARDIAN"), unlinked)).isFalse();
        assertThat(playerAccess.canAccess(guardianUser, java.util.Set.of("GUARDIAN"), formerlyLinked))
                .as("un vínculo vencido no da acceso").isFalse();
        assertThat(playerAccess.canAccess(guardianUser, java.util.Set.of("COORDINATOR"), unlinked)).isTrue();
    }

    private UUID newPlayer() {
        return jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date)
                VALUES ('Jugador', 'Prueba', 'TI', ?, '2014-05-05') RETURNING id
                """, UUID.class, "ti-" + UUID.randomUUID().toString().substring(0, 20));
    }
}
