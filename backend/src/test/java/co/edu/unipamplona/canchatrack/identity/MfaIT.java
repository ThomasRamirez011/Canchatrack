package co.edu.unipamplona.canchatrack.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import co.edu.unipamplona.canchatrack.identity.application.SecurityMaintenanceService;
import co.edu.unipamplona.canchatrack.identity.domain.Base32;
import co.edu.unipamplona.canchatrack.identity.domain.Totp;

/**
 * Segundo factor (TOTP) contra PostgreSQL real: el coordinador y el comité no obtienen sesión con la contraseña sola,
 * configuran el factor la primera vez, presentan un código en cada inicio de sesión, no pueden repetir un código ni un
 * código de respaldo, y un coordinador puede reiniciar el factor de otra persona. El contexto activa el MFA, que el
 * resto de las pruebas mantiene apagado.
 */
@SpringBootTest(properties = "canchatrack.identity.mfa.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MfaIT {

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
    private SecurityMaintenanceService maintenance;

    /** Cuenta ya enrolada: lo necesario para seguir operando con ella. */
    private record Enrolled(String email, UUID id, byte[] secret, String accessToken, String refreshToken,
                            List<String> backupCodes) {
    }

    // ---------- utilidades ----------

    private String account(String role) {
        String email = "mfa-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject(
                "INSERT INTO users (email, full_name, password_hash, email_verified_at) VALUES (?, ?, ?, now()) RETURNING id",
                UUID.class, email, "Cuenta " + role, encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, role);
        return email;
    }

    private UUID idOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", UUID.class, email);
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

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions login(String email) throws Exception {
        return postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null);
    }

    private String challengeOf(String email, String expectedStep) throws Exception {
        JsonNode challenge = read(login(email).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.step").value(expectedStep))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist()));
        assertThat(challenge.get("expiresIn").asLong()).isBetween(1L, 300L);
        return challenge.get("challengeToken").asText();
    }

    /** Código de un intervalo relativo al actual (0 = ahora, 1 = el siguiente, que el servidor también acepta). */
    private static String code(byte[] secret, int stepOffset) {
        return Totp.code(secret, Totp.stepAt(Instant.now()) + stepOffset);
    }

    /** Un código con forma de TOTP que no vale en ningún intervalo cercano. */
    private static String wrongCode(byte[] secret) {
        long step = Totp.stepAt(Instant.now());
        Set<String> valid = new java.util.HashSet<>();
        for (long s = step - 3; s <= step + 3; s++) {
            valid.add(Totp.code(secret, s));
        }
        for (int candidate = 0; candidate < 1_000_000; candidate += 7919) {
            String code = String.format("%06d", candidate);
            if (!valid.contains(code)) {
                return code;
            }
        }
        throw new IllegalStateException("no hay código inválido");
    }

    /** Recorre la configuración completa: contraseña, secreto, primer código. Usa el intervalo actual. */
    private Enrolled enroll(String email) throws Exception {
        String challenge = challengeOf(email, "ENROLL");
        JsonNode secret = read(postJson("/auth/mfa/enrollment", Map.of("challengeToken", challenge), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").exists()));
        byte[] bytes = Base32.decode(secret.get("secret").asText());
        JsonNode done = read(postJson("/auth/mfa/enrollment/confirm",
                Map.of("challengeToken", challenge, "code", code(bytes, 0)), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.accessToken").exists())
                .andExpect(jsonPath("$.backupCodes.length()").value(10)));
        List<String> codes = new ArrayList<>();
        done.get("backupCodes").forEach(node -> codes.add(node.asText()));
        return new Enrolled(email, idOf(email), bytes, done.get("session").get("accessToken").asText(),
                done.get("session").get("refreshToken").asText(), codes);
    }

    private long audited(String action, UUID entity) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND entity_id = ?",
                Long.class, action, entity);
    }

    // ---------- configurar el factor ----------

    @Test
    void aCoordinatorMustSetTheFactorUpBeforeGettingASession() throws Exception {
        String email = account("COORDINATOR");

        Enrolled enrolled = enroll(email);

        getJson("/auth/mfa", enrolled.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.backupCodesRemaining").value(10));
        getJson("/auth/me", enrolled.accessToken()).andExpect(status().isOk());
        assertThat(audited("MFA_ENROLLED", enrolled.id())).isEqualTo(1L);
        assertThat(audited("MFA_ENROLLMENT_STARTED", enrolled.id())).isEqualTo(1L);
    }

    @Test
    void theSecretAndTheBackupCodesAreNeverStoredInTheClear() throws Exception {
        Enrolled enrolled = enroll(account("COMMITTEE"));

        byte[] sealed = jdbc.queryForObject("SELECT secret_sealed FROM mfa_factors WHERE user_id = ?", byte[].class,
                enrolled.id());
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(enrolled.secret(), java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThat(sealed.length).isEqualTo(12 + Totp.SECRET_BYTES + 16);
        List<String> hashes = jdbc.queryForList("SELECT code_hash FROM mfa_backup_codes WHERE user_id = ?", String.class,
                enrolled.id());
        assertThat(hashes).hasSize(10).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
        enrolled.backupCodes().forEach(code -> assertThat(hashes).doesNotContain(code)
                .doesNotContain(code.replace("-", "")));
        assertThat(jdbc.queryForList("SELECT token_hash FROM mfa_challenges WHERE user_id = ?", String.class, enrolled.id()))
                .allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void loginWithOnlyThePasswordNeverGivesASessionToARequiredRole() throws Exception {
        String email = account("COORDINATOR");

        login(email).andExpect(status().isAccepted()).andExpect(jsonPath("$.accessToken").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, idOf(email)))
                .isZero();
        postJson("/auth/login", Map.of("email", email, "password", "otra-clave-1234"), null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rolesThatAreNotRequiredLogInWithJustThePassword() throws Exception {
        String email = account("COACH");

        String token = read(login(email).andExpect(status().isOk())).get("accessToken").asText();

        getJson("/auth/mfa", token).andExpect(status().isOk()).andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.enrolled").value(false));
    }

    @Test
    void aWrongEnrollmentCodeDoesNotActivateTheFactor() throws Exception {
        String email = account("COORDINATOR");
        String challenge = challengeOf(email, "ENROLL");
        byte[] secret = Base32.decode(read(postJson("/auth/mfa/enrollment", Map.of("challengeToken", challenge), null)
                .andExpect(status().isOk())).get("secret").asText());

        postJson("/auth/mfa/enrollment/confirm", Map.of("challengeToken", challenge, "code", wrongCode(secret)), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));

        assertThat(jdbc.queryForObject("SELECT status FROM mfa_factors WHERE user_id = ?", String.class, idOf(email)))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_backup_codes WHERE user_id = ?", Integer.class, idOf(email)))
                .isZero();
    }

    // ---------- verificar el código ----------

    @Test
    void afterEnrollingEachLoginAsksForACodeAndTheSessionWorks() throws Exception {
        Enrolled enrolled = enroll(account("COORDINATOR"));

        String challenge = challengeOf(enrolled.email(), "VERIFY");
        JsonNode session = read(postJson("/auth/mfa/verify",
                Map.of("challengeToken", challenge, "code", code(enrolled.secret(), 1)), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.roles[0]").value("COORDINATOR")));

        getJson("/auth/me", session.get("accessToken").asText()).andExpect(status().isOk());
        postJson("/auth/refresh", Map.of("refreshToken", session.get("refreshToken").asText()), null)
                .andExpect(status().isOk());
        assertThat(audited("MFA_VERIFIED", enrolled.id())).isEqualTo(1L);
    }

    @Test
    void aCodeCannotBeUsedTwice() throws Exception {
        Enrolled enrolled = enroll(account("COORDINATOR"));
        String code = code(enrolled.secret(), 1);
        postJson("/auth/mfa/verify", Map.of("challengeToken", challengeOf(enrolled.email(), "VERIFY"), "code", code), null)
                .andExpect(status().isOk());

        postJson("/auth/mfa/verify", Map.of("challengeToken", challengeOf(enrolled.email(), "VERIFY"), "code", code), null)
                .andExpect(status().isUnauthorized());
        // El que sirvió para configurar tampoco se puede reutilizar.
        postJson("/auth/mfa/verify",
                Map.of("challengeToken", challengeOf(enrolled.email(), "VERIFY"), "code", code(enrolled.secret(), 0)), null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBackupCodeWorksOnceAndIsCounted() throws Exception {
        Enrolled enrolled = enroll(account("COMMITTEE"));
        String backup = enrolled.backupCodes().get(0);

        postJson("/auth/mfa/verify", Map.of("challengeToken", challengeOf(enrolled.email(), "VERIFY"), "code", backup), null)
                .andExpect(status().isOk());
        postJson("/auth/mfa/verify", Map.of("challengeToken", challengeOf(enrolled.email(), "VERIFY"), "code", backup), null)
                .andExpect(status().isUnauthorized());

        String token = read(postJson("/auth/mfa/verify",
                Map.of("challengeToken", challengeOf(enrolled.email(), "VERIFY"),
                        "code", enrolled.backupCodes().get(1).replace("-", "").toLowerCase()), null)
                .andExpect(status().isOk())).get("accessToken").asText();
        getJson("/auth/mfa", token).andExpect(jsonPath("$.backupCodesRemaining").value(8));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_backup_codes WHERE user_id = ? AND used_at IS NOT NULL",
                Integer.class, enrolled.id())).isEqualTo(2);
    }

    @Test
    void fiveWrongCodesLockTheAccount() throws Exception {
        Enrolled enrolled = enroll(account("COORDINATOR"));
        String challenge = challengeOf(enrolled.email(), "VERIFY");

        for (int i = 0; i < 5; i++) {
            postJson("/auth/mfa/verify", Map.of("challengeToken", challenge, "code", wrongCode(enrolled.secret())), null)
                    .andExpect(status().isUnauthorized());
        }

        assertThat(audited("MFA_VERIFY_FAILED", enrolled.id())).isEqualTo(5L);
        postJson("/auth/mfa/verify", Map.of("challengeToken", challenge, "code", code(enrolled.secret(), 1)), null)
                .andExpect(status().isUnauthorized());
        login(enrolled.email()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM users WHERE id = ?", Boolean.class,
                enrolled.id())).isTrue();
    }

    @Test
    void aChallengeIsBoundToItsStepAndExpires() throws Exception {
        Enrolled enrolled = enroll(account("COORDINATOR"));
        String verify = challengeOf(enrolled.email(), "VERIFY");
        postJson("/auth/mfa/enrollment", Map.of("challengeToken", verify), null).andExpect(status().isUnauthorized());

        String freshAccount = account("COMMITTEE");
        String enrollChallenge = challengeOf(freshAccount, "ENROLL");
        postJson("/auth/mfa/verify", Map.of("challengeToken", enrollChallenge, "code", "123456"), null)
                .andExpect(status().isUnauthorized());

        jdbc.update("UPDATE mfa_challenges SET created_at = now() - interval '10 minutes', "
                + "expires_at = now() - interval '5 minutes' WHERE user_id = ?", enrolled.id());
        postJson("/auth/mfa/verify", Map.of("challengeToken", verify, "code", code(enrolled.secret(), 1)), null)
                .andExpect(status().isUnauthorized());
        postJson("/auth/mfa/verify", Map.of("challengeToken", "inventado-" + UUID.randomUUID(), "code", "123456"), null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSecondFactorEndpointsRequireTheRightCredentials() throws Exception {
        getJson("/auth/mfa", null).andExpect(status().isUnauthorized());
        postJson("/auth/mfa/backup-codes", Map.of("code", "123456"), null).andExpect(status().isUnauthorized());
        postJson("/users/" + UUID.randomUUID() + "/mfa/reset", Map.of(), null).andExpect(status().isUnauthorized());
        postJson("/auth/mfa/verify", Map.of("challengeToken", "", "code", ""), null)
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- códigos de respaldo y reinicio ----------

    @Test
    void backupCodesCanBeReplacedWithAFreshCode() throws Exception {
        Enrolled enrolled = enroll(account("COORDINATOR"));

        postJson("/auth/mfa/backup-codes", Map.of("code", wrongCode(enrolled.secret())), enrolled.accessToken())
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].field").value("code"));
        JsonNode fresh = read(postJson("/auth/mfa/backup-codes", Map.of("code", code(enrolled.secret(), 1)),
                enrolled.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.backupCodes.length()").value(10)));

        assertThat(fresh.get("backupCodes").get(0).asText()).isNotIn(enrolled.backupCodes());
        postJson("/auth/mfa/verify",
                Map.of("challengeToken", challengeOf(enrolled.email(), "VERIFY"), "code", enrolled.backupCodes().get(0)), null)
                .andExpect(status().isUnauthorized());
        postJson("/auth/mfa/verify",
                Map.of("challengeToken", challengeOf(enrolled.email(), "VERIFY"),
                        "code", fresh.get("backupCodes").get(0).asText()), null)
                .andExpect(status().isOk());
    }

    @Test
    void aCoordinatorCanResetAnotherPersonsFactor() throws Exception {
        Enrolled admin = enroll(account("COORDINATOR"));
        Enrolled victim = enroll(account("COMMITTEE"));

        postJson("/users/" + victim.id() + "/mfa/reset", Map.of(), admin.accessToken()).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_factors WHERE user_id = ?", Integer.class, victim.id()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_backup_codes WHERE user_id = ?", Integer.class, victim.id()))
                .isZero();
        postJson("/auth/refresh", Map.of("refreshToken", victim.refreshToken()), null).andExpect(status().isUnauthorized());
        challengeOf(victim.email(), "ENROLL");
        assertThat(audited("MFA_RESET", victim.id())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_events WHERE action = 'MFA_RESET' AND entity_id = ?",
                UUID.class, victim.id())).isEqualTo(admin.id());
    }

    @Test
    void resetRulesProtectTheFactor() throws Exception {
        Enrolled admin = enroll(account("COORDINATOR"));
        Enrolled committee = enroll(account("COMMITTEE"));

        postJson("/users/" + admin.id() + "/mfa/reset", Map.of(), admin.accessToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"));
        postJson("/users/" + admin.id() + "/mfa/reset", Map.of(), committee.accessToken())
                .andExpect(status().isForbidden());
        postJson("/users/" + UUID.randomUUID() + "/mfa/reset", Map.of(), admin.accessToken())
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT status FROM mfa_factors WHERE user_id = ?", String.class, admin.id()))
                .isEqualTo("ACTIVE");
    }

    // ---------- limpieza ----------

    @Test
    void theMaintenanceJobPurgesOldChallengesButKeepsFreshOnes() throws Exception {
        String stale = account("COORDINATOR");
        String fresh = account("COMMITTEE");
        challengeOf(stale, "ENROLL");
        challengeOf(fresh, "ENROLL");
        jdbc.update("UPDATE mfa_challenges SET created_at = now() - interval '12 days', expires_at = now() - interval '11 days' "
                + "WHERE user_id = ?", idOf(stale));

        SecurityMaintenanceService.Result result = maintenance.purge();

        assertThat(result.mfaChallenges()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_challenges WHERE user_id = ?", Integer.class, idOf(stale)))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_challenges WHERE user_id = ?", Integer.class, idOf(fresh)))
                .isEqualTo(1);
    }
}
