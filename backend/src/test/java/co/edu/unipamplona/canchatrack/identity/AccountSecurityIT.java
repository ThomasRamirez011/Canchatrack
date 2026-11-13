package co.edu.unipamplona.canchatrack.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import co.edu.unipamplona.canchatrack.notifications.application.EmailDeliveryException;
import co.edu.unipamplona.canchatrack.notifications.application.EmailSender;
import co.edu.unipamplona.canchatrack.notifications.application.NotificationProcessor;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/**
 * Seguridad de cuentas contra PostgreSQL real (bloque 4): confirmación del correo como condición para iniciar sesión,
 * recuperación y cambio de contraseña con cierre de sesiones, enlaces de un solo uso que no quedan en claro, límite de
 * tasa persistido en base y limpieza programada. A diferencia del resto de pruebas, este contexto activa la exigencia de
 * correo confirmado y el límite de tasa (con topes bajos); cada prueba usa su propia IP para no compartir el contador.
 */
@SpringBootTest(properties = {
        "canchatrack.identity.require-verified-email=true",
        "canchatrack.identity.rate-limit.enabled=true",
        "canchatrack.identity.rate-limit.login-per-ip=8",
        "canchatrack.identity.rate-limit.register-per-ip=3",
        "canchatrack.identity.rate-limit.recovery-per-ip=50",
        "canchatrack.identity.rate-limit.recovery-per-email=2",
        "canchatrack.identity.rate-limit.confirm-per-ip=4"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AccountSecurityIT.SenderConfig.class})
@ActiveProfiles("test")
class AccountSecurityIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String NEW_PASSWORD = "Otra-Clave-Nueva-456";
    private static final String API = "/api/v1";
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);
    private static final AtomicInteger NEXT_DOCUMENT = new AtomicInteger(700_000_000);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private NotificationProcessor processor;
    @Autowired
    private SecurityMaintenanceService maintenance;
    @Autowired
    private CapturingSender sender;

    /** Cada prueba (instancia nueva) tiene su IP: el límite de tasa se cuenta por IP y no debe filtrarse entre pruebas. */
    private final String ip = nextIp();

    // ---------- utilidades ----------

    private static String nextIp() {
        int n = NEXT_IP.getAndIncrement();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    private ResultActions postJson(String path, Object body, String bearer, String fromIp) throws Exception {
        var request = post(API + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                .with(r -> {
                    r.setRemoteAddr(fromIp);
                    return r;
                });
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return postJson(path, body, null, ip);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String registerGuardian(String fromIp) throws Exception {
        String email = "reg-" + UUID.randomUUID() + "@club.test";
        postJson("/guardians", Map.of("email", email, "fullName", "Ana Acudiente", "password", PASSWORD,
                "documentType", "CC", "documentNumber", String.valueOf(NEXT_DOCUMENT.getAndIncrement())), null, fromIp)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerified").value(false));
        return email;
    }

    /** Cuenta de personal ya verificada (las creadas por el coordinador no pasan por el correo de confirmación). */
    private String verifiedAccount() {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject(
                "INSERT INTO users (email, full_name, password_hash, email_verified_at) VALUES (?, ?, ?, now()) RETURNING id",
                UUID.class, email, "Cuenta de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'COACH'", id);
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        return postJson("/auth/login", Map.of("email", email, "password", password));
    }

    private JsonNode loginOk(String email, String password) throws Exception {
        return read(login(email, password).andExpect(status().isOk()));
    }

    /** El token en claro solo existe en la cola hasta que se entrega: se lee de ahí, como lo haría el proveedor de correo. */
    private String pendingToken(String type, String email) {
        return jdbc.queryForObject("""
                SELECT payload->>'token' FROM notifications
                WHERE notification_type = ? AND lower(recipient_email) = lower(?) AND payload->>'token' IS NOT NULL
                ORDER BY created_at DESC LIMIT 1
                """, String.class, type, email);
    }

    private int notices(String type, String email) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE notification_type = ? AND lower(recipient_email) = lower(?)",
                Integer.class, type, email);
    }

    private long audited(String action, String email) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND actor_id = "
                + "(SELECT id FROM users WHERE lower(email) = lower(?))", Long.class, action, email);
    }

    private void deliverEverything() {
        for (int pass = 0; pass < 20 && processor.processDue() > 0; pass++) {
            // vacía la cola: cada pasada reclama una tanda
        }
    }

    private static Map<String, String> tokenBody(String token) {
        return Map.of("token", token);
    }

    // ---------- confirmación del correo ----------

    @Test
    void anUnconfirmedGuardianCannotLogInUntilTheEmailLinkIsRedeemed() throws Exception {
        String email = registerGuardian(ip);

        login(email, PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        // Con la contraseña errónea no se revela nada: sigue el 401 genérico.
        login(email, "otra-clave-1234").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));

        String token = pendingToken("EMAIL_VERIFICATION", email);
        postJson("/auth/email-verification/confirm", tokenBody(token)).andExpect(status().isNoContent());

        JsonNode session = loginOk(email, PASSWORD);
        assertThat(session.get("user").get("emailVerified").asBoolean()).isTrue();
        assertThat(audited("EMAIL_VERIFIED", email)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE lower(email) = lower(?)",
                Boolean.class, email)).isTrue();
    }

    @Test
    void theVerificationLinkWorksOnlyOnce() throws Exception {
        String email = registerGuardian(ip);
        String token = pendingToken("EMAIL_VERIFICATION", email);

        postJson("/auth/email-verification/confirm", tokenBody(token)).andExpect(status().isNoContent());
        postJson("/auth/email-verification/confirm", tokenBody(token)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("token"));
        postJson("/auth/email-verification/confirm", tokenBody("inventado-" + UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].field").value("token"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE used_at IS NOT NULL AND user_id = "
                + "(SELECT id FROM users WHERE lower(email) = lower(?))", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void resendingReplacesTheOldLinkAndStaysSilentForUnknownOrConfirmedAccounts() throws Exception {
        String email = registerGuardian(ip);
        String first = pendingToken("EMAIL_VERIFICATION", email);

        postJson("/auth/email-verification/request", Map.of("email", email)).andExpect(status().isAccepted());
        String second = pendingToken("EMAIL_VERIFICATION", email);
        assertThat(second).isNotEqualTo(first);
        assertThat(notices("EMAIL_VERIFICATION", email)).isEqualTo(2);
        postJson("/auth/email-verification/confirm", tokenBody(first)).andExpect(status().isUnprocessableEntity());
        postJson("/auth/email-verification/confirm", tokenBody(second)).andExpect(status().isNoContent());

        // Ya confirmada: pedir otro enlace responde igual y no envía nada.
        postJson("/auth/email-verification/request", Map.of("email", email)).andExpect(status().isAccepted());
        assertThat(notices("EMAIL_VERIFICATION", email)).isEqualTo(2);

        // Cuenta inexistente: misma respuesta, sin rastro en la cola.
        String stranger = "nadie-" + UUID.randomUUID() + "@club.test";
        String answer = postJson("/auth/email-verification/request", Map.of("email", stranger)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(answer).isEmpty();
        assertThat(notices("EMAIL_VERIFICATION", stranger)).isZero();
    }

    @Test
    void theLinkIsNeverStoredInTheClearAndLeavesTheQueueOnceDelivered() throws Exception {
        String email = registerGuardian(ip);
        String token = pendingToken("EMAIL_VERIFICATION", email);

        // En la base solo está el hash del token, no el valor que viaja en el correo.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE token_hash = ?", Integer.class, token)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
                Integer.class, token)).isEqualTo(1);

        deliverEverything();

        OutboundEmail delivered = sender.lastContaining(email, token);
        assertThat(delivered.body()).contains(token);
        assertThat(jdbc.queryForObject("SELECT status FROM notifications WHERE notification_type = 'EMAIL_VERIFICATION' "
                + "AND lower(recipient_email) = lower(?)", String.class, email)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM notifications WHERE notification_type = 'EMAIL_VERIFICATION' "
                + "AND lower(recipient_email) = lower(?)", String.class, email)).doesNotContain(token).doesNotContain("\"token\"");
    }

    // ---------- recuperación de contraseña ----------

    @Test
    void resetByEmailLinkChangesThePasswordClosesSessionsAndCannotBeReplayed() throws Exception {
        String email = verifiedAccount();
        String refresh = loginOk(email, PASSWORD).get("refreshToken").asText();

        postJson("/auth/password-reset/request", Map.of("email", email)).andExpect(status().isAccepted());
        String token = pendingToken("PASSWORD_RESET", email);

        // Un error de validación se informa sin gastar el enlace.
        postJson("/auth/password-reset/confirm", Map.of("token", token, "newPassword", "corta"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')]").isNotEmpty());
        postJson("/auth/password-reset/confirm", Map.of("token", token, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent());

        login(email, PASSWORD).andExpect(status().isUnauthorized());
        loginOk(email, NEW_PASSWORD);
        postJson("/auth/refresh", Map.of("refreshToken", refresh)).andExpect(status().isUnauthorized());
        postJson("/auth/password-reset/confirm", Map.of("token", token, "newPassword", "Tercera-Clave-789"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].field").value("token"));
        assertThat(notices("PASSWORD_CHANGED", email)).isEqualTo(1);
        assertThat(audited("PASSWORD_RESET_COMPLETED", email)).isEqualTo(1L);
    }

    @Test
    void resettingAnUnconfirmedGuardianAlsoConfirmsTheEmail() throws Exception {
        String email = registerGuardian(ip);
        login(email, PASSWORD).andExpect(status().isForbidden());

        postJson("/auth/password-reset/request", Map.of("email", email)).andExpect(status().isAccepted());
        String token = pendingToken("PASSWORD_RESET", email);
        postJson("/auth/password-reset/confirm", Map.of("token", token, "newPassword", NEW_PASSWORD)).andExpect(status().isNoContent());

        // Recibir el enlace en ese buzón demuestra que es del titular.
        assertThat(loginOk(email, NEW_PASSWORD).get("user").get("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void aResetRequestLooksTheSameWhetherOrNotTheAccountExists() throws Exception {
        String email = verifiedAccount();
        String stranger = "nadie-" + UUID.randomUUID() + "@club.test";

        var known = postJson("/auth/password-reset/request", Map.of("email", email)).andExpect(status().isAccepted()).andReturn().getResponse();
        var unknown = postJson("/auth/password-reset/request", Map.of("email", stranger)).andExpect(status().isAccepted()).andReturn().getResponse();

        assertThat(unknown.getContentAsString()).isEqualTo(known.getContentAsString()).isEmpty();
        assertThat(notices("PASSWORD_RESET", email)).isEqualTo(1);
        assertThat(notices("PASSWORD_RESET", stranger)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE purpose = 'PASSWORD_RESET' AND user_id = "
                + "(SELECT id FROM users WHERE lower(email) = lower(?))", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void aNewResetRequestInvalidatesThePreviousLink() throws Exception {
        String email = verifiedAccount();
        postJson("/auth/password-reset/request", Map.of("email", email)).andExpect(status().isAccepted());
        String first = pendingToken("PASSWORD_RESET", email);
        postJson("/auth/password-reset/request", Map.of("email", email)).andExpect(status().isAccepted());
        String second = pendingToken("PASSWORD_RESET", email);

        postJson("/auth/password-reset/confirm", Map.of("token", first, "newPassword", NEW_PASSWORD))
                .andExpect(status().isUnprocessableEntity());
        postJson("/auth/password-reset/confirm", Map.of("token", second, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent());
    }

    @Test
    void anExpiredResetLinkIsRejected() throws Exception {
        String email = verifiedAccount();
        postJson("/auth/password-reset/request", Map.of("email", email)).andExpect(status().isAccepted());
        String token = pendingToken("PASSWORD_RESET", email);
        jdbc.update("UPDATE account_tokens SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' "
                + "WHERE purpose = 'PASSWORD_RESET' AND user_id = (SELECT id FROM users WHERE lower(email) = lower(?))", email);

        postJson("/auth/password-reset/confirm", Map.of("token", token, "newPassword", NEW_PASSWORD))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].field").value("token"));
        login(email, PASSWORD).andExpect(status().isOk());
    }

    // ---------- cambio de contraseña con sesión ----------

    @Test
    void changingThePasswordRequiresTheCurrentOneAndClosesEverySession() throws Exception {
        String email = verifiedAccount();
        JsonNode session = loginOk(email, PASSWORD);
        String access = session.get("accessToken").asText();
        String refresh = session.get("refreshToken").asText();

        postJson("/auth/password", Map.of("currentPassword", "no-es-la-clave-1", "newPassword", NEW_PASSWORD), access, ip)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));
        postJson("/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD), access, ip)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')]").isNotEmpty());
        postJson("/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD), access, ip)
                .andExpect(status().isNoContent());

        postJson("/auth/refresh", Map.of("refreshToken", refresh)).andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        loginOk(email, NEW_PASSWORD);
        assertThat(notices("PASSWORD_CHANGED", email)).isEqualTo(1);
        assertThat(audited("PASSWORD_CHANGED", email)).isEqualTo(1L);
        assertThat(audited("PASSWORD_CHANGE_DENIED", email)).isEqualTo(1L);
    }

    @Test
    void changingThePasswordWithoutASessionIsRejected() throws Exception {
        postJson("/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guessingTheCurrentPasswordWithAStolenSessionLocksTheAccount() throws Exception {
        String email = verifiedAccount();
        String access = loginOk(email, PASSWORD).get("accessToken").asText();

        for (int i = 0; i < 5; i++) {
            postJson("/auth/password", Map.of("currentPassword", "intento-" + i + "-erroneo", "newPassword", NEW_PASSWORD), access, ip)
                    .andExpect(status().isUnprocessableEntity());
        }
        // Bloqueada: ni siquiera la contraseña correcta cambia nada y se indica cuánto esperar.
        postJson("/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD), access, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        login(email, PASSWORD).andExpect(status().isUnauthorized());
    }

    // ---------- límite de tasa ----------

    @Test
    void loginAttemptsFromOneAddressAreCappedAndOtherAddressesAreUnaffected() throws Exception {
        String stranger = "nadie-" + UUID.randomUUID() + "@club.test";
        for (int i = 0; i < 8; i++) {
            login(stranger, "cualquier-clave-1").andExpect(status().isUnauthorized());
        }

        var limited = login(stranger, "cualquier-clave-1").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(header().exists("Retry-After")).andReturn().getResponse();
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 900L + 1);

        postJson("/auth/login", Map.of("email", stranger, "password", "cualquier-clave-1"), null, nextIp())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theLimitStoresOnlyHashesOfTheAddressAndTheEmail() throws Exception {
        String email = "victima-" + UUID.randomUUID() + "@club.test";
        postJson("/auth/password-reset/request", Map.of("email", email)).andExpect(status().isAccepted());

        List<String> stored = jdbc.queryForList("SELECT subject_hash FROM rate_limit_events", String.class);
        assertThat(stored).isNotEmpty().allSatisfy(hash -> assertThat(hash).matches("[0-9a-f]{64}"));
        assertThat(String.join(",", stored)).doesNotContain(ip).doesNotContain(email);
    }

    @Test
    void recoveryRequestsAreCappedPerEmailEvenFromDifferentAddresses() throws Exception {
        String email = verifiedAccount();
        postJson("/auth/password-reset/request", Map.of("email", email), null, nextIp()).andExpect(status().isAccepted());
        postJson("/auth/password-reset/request", Map.of("email", email), null, nextIp()).andExpect(status().isAccepted());

        postJson("/auth/password-reset/request", Map.of("email", email), null, nextIp())
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // El tope cuenta intentos, no cuentas: un correo inexistente se limita igual y no delata nada.
        String stranger = "nadie-" + UUID.randomUUID() + "@club.test";
        postJson("/auth/password-reset/request", Map.of("email", stranger), null, nextIp()).andExpect(status().isAccepted());
        postJson("/auth/password-reset/request", Map.of("email", stranger), null, nextIp()).andExpect(status().isAccepted());
        postJson("/auth/password-reset/request", Map.of("email", stranger), null, nextIp()).andExpect(status().isTooManyRequests());
        assertThat(notices("PASSWORD_RESET", email)).isEqualTo(2);
    }

    @Test
    void redeemingLinksIsCappedPerAddress() throws Exception {
        for (int i = 0; i < 4; i++) {
            postJson("/auth/email-verification/confirm", tokenBody("adivinando-" + i)).andExpect(status().isUnprocessableEntity());
        }

        postJson("/auth/email-verification/confirm", tokenBody("adivinando-5")).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        postJson("/auth/password-reset/confirm", Map.of("token", "adivinando-6", "newPassword", NEW_PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void guardianRegistrationIsCappedPerAddressAndTheRejectedOneCreatesNothing() throws Exception {
        for (int i = 0; i < 3; i++) {
            registerGuardian(ip);
        }

        String email = "rechazado-" + UUID.randomUUID() + "@club.test";
        postJson("/guardians", Map.of("email", email, "fullName", "Ana Acudiente", "password", PASSWORD,
                "documentType", "CC", "documentNumber", String.valueOf(NEXT_DOCUMENT.getAndIncrement())))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email)).isZero();
    }

    // ---------- limpieza ----------

    @Test
    void theMaintenanceJobPurgesOldTokensAndRateEventsButKeepsRecentOnes() {
        String email = verifiedAccount();
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = lower(?)", UUID.class, email);
        UUID oldToken = UUID.randomUUID();
        UUID freshToken = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_tokens (id, user_id, purpose, token_hash, created_at, expires_at, used_at)
                VALUES (?, ?, 'PASSWORD_RESET', md5(random()::text) || md5(random()::text),
                        now() - interval '20 days', now() - interval '19 days', now() - interval '19 days')
                """, oldToken, userId);
        jdbc.update("""
                INSERT INTO account_tokens (id, user_id, purpose, token_hash, created_at, expires_at)
                VALUES (?, ?, 'PASSWORD_RESET', md5(random()::text) || md5(random()::text), now(), now() + interval '30 minutes')
                """, freshToken, userId);
        String marker = jdbc.queryForObject("SELECT md5(random()::text) || md5(random()::text)", String.class);
        jdbc.update("INSERT INTO rate_limit_events (bucket, subject_hash, occurred_at) VALUES ('LOGIN_IP', ?, now() - interval '2 days')", marker);
        jdbc.update("INSERT INTO rate_limit_events (bucket, subject_hash, occurred_at) VALUES ('LOGIN_IP', ?, now())", marker);

        SecurityMaintenanceService.Result result = maintenance.purge();

        assertThat(result.accountTokens()).isGreaterThanOrEqualTo(1);
        assertThat(result.rateLimitEvents()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE id = ?", Integer.class, oldToken)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_tokens WHERE id = ?", Integer.class, freshToken)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_events WHERE subject_hash = ?", Integer.class, marker)).isEqualTo(1);
    }

    // ---------- correo de prueba ----------

    @TestConfiguration(proxyBeanMethods = false)
    static class SenderConfig {

        @Bean
        @Primary
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    static final class CapturingSender implements EmailSender {

        private final List<OutboundEmail> delivered = new CopyOnWriteArrayList<>();

        @Override
        public String send(OutboundEmail email) throws EmailDeliveryException {
            delivered.add(email);
            return "capture-" + UUID.randomUUID();
        }

        /** Último correo entregado a la dirección cuyo cuerpo trae el valor indicado (el enlace o código en claro). */
        OutboundEmail lastContaining(String address, String text) {
            List<OutboundEmail> matches = new ArrayList<>(delivered.stream()
                    .filter(e -> e.to().equalsIgnoreCase(address) && e.body().contains(text)).toList());
            if (matches.isEmpty()) {
                throw new AssertionError("no se entregó a " + address + " un correo con el valor esperado");
            }
            return matches.get(matches.size() - 1);
        }
    }
}
