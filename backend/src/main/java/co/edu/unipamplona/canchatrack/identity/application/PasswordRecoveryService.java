package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditRecord.Outcome;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.domain.AccountStatus;
import co.edu.unipamplona.canchatrack.identity.domain.AccountToken;
import co.edu.unipamplona.canchatrack.identity.domain.AccountTokenPurpose;
import co.edu.unipamplona.canchatrack.identity.domain.Emails;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.PasswordPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Recuperación (por enlace de un solo uso) y cambio autenticado de la contraseña.
 *
 * <p>Principios: solicitar un restablecimiento responde igual exista o no el correo; el token solo vive en la base como
 * hash y se invalida al usarse, al emitir otro o al vencer; cambiar la contraseña por cualquiera de las dos vías cierra
 * todas las sesiones de la cuenta y avisa por correo. No se pueden revocar los access tokens ya emitidos (viven 15
 * minutos), límite aceptado de un JWT sin estado.
 */
@Service
public class PasswordRecoveryService {

    static final String ACTION_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    static final String ACTION_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    static final String ACTION_PASSWORD_CHANGED = "PASSWORD_CHANGED";
    static final String ACTION_CHANGE_DENIED = "PASSWORD_CHANGE_DENIED";
    static final String NOTICE_RESET = "PASSWORD_RESET";
    static final String NOTICE_CHANGED = "PASSWORD_CHANGED";
    static final String RELATED_ENTITY = "user";
    static final String REVOKE_RESET = "PASSWORD_RESET";
    static final String REVOKE_CHANGE = "PASSWORD_CHANGED";
    private static final String INVALID_LINK = "El enlace no es válido o ya venció.";

    /** Resultado de pedir el cambio de contraseña estando autenticado. */
    public sealed interface ChangeResult {

        record Changed() implements ChangeResult {
        }

        record WrongCurrentPassword() implements ChangeResult {
        }

        /** La cuenta está bloqueada por intentos fallidos; {@code retryAfterSeconds} es lo que falta para el desbloqueo. */
        record Locked(long retryAfterSeconds) implements ChangeResult {
        }
    }

    private final UserAccountRepository users;
    private final AccountTokenStore tokens;
    private final RefreshTokenStore refreshTokens;
    private final PasswordHasher hasher;
    private final NotificationQueue queue;
    private final AuditWriter audit;
    private final AuthSettings authSettings;
    private final AccountSecuritySettings settings;
    private final Clock clock;

    public PasswordRecoveryService(UserAccountRepository users, AccountTokenStore tokens,
                                   RefreshTokenStore refreshTokens, PasswordHasher hasher, NotificationQueue queue,
                                   AuditWriter audit, AuthSettings authSettings, AccountSecuritySettings settings,
                                   Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.queue = queue;
        this.audit = audit;
        this.authSettings = authSettings;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Envía el enlace de restablecimiento si la cuenta existe, está activa y usa contraseña. Nunca dice si el correo
     * existe: en todos los casos devuelve sin error.
     */
    @Transactional
    public void requestReset(String email, ClientOrigin origin) {
        Instant now = clock.instant();
        String normalized = Emails.normalize(email);
        Optional<UserAccount> found = Emails.isValid(normalized) ? users.findByEmailForUpdate(normalized) : Optional.empty();
        if (found.isEmpty() || found.get().status() != AccountStatus.ACTIVE || found.get().passwordHash() == null) {
            audit(ACTION_RESET_REQUESTED, found.map(UserAccount::id).orElse(null), Outcome.DENIED,
                    found.isEmpty() ? "UNKNOWN_USER" : "ACCOUNT_NOT_ELIGIBLE", origin);
            return;
        }
        UserAccount account = found.get();
        tokens.revokeOutstanding(account.id(), AccountTokenPurpose.PASSWORD_RESET, now);
        String raw = OpaqueToken.generate();
        UUID tokenId = UUID.randomUUID();
        tokens.insert(new AccountTokenStore.NewAccountToken(tokenId, account.id(), AccountTokenPurpose.PASSWORD_RESET,
                OpaqueToken.sha256Hex(raw), now, now.plus(settings.resetTtl()), ip(origin)));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("token", raw);
        String url = settings.resetUrl(raw);
        if (!url.isEmpty()) {
            payload.put("url", url);
        }
        payload.put("validityMinutes", Long.toString(Math.max(1, settings.resetTtl().toMinutes())));
        queue.enqueue(NOTICE_RESET, List.of(account.id()), "Restablecer su contraseña", payload, RELATED_ENTITY,
                tokenId, "reset");
        audit(ACTION_RESET_REQUESTED, account.id(), Outcome.SUCCESS, null, origin);
    }

    /**
     * Fija la contraseña nueva con el token del correo. Un token inexistente, usado, revocado o vencido produce el
     * mismo error. Todo error de validación se devuelve junto, sin consumir el token.
     */
    @Transactional
    public void confirmReset(String rawToken, String newPassword, ClientOrigin origin) {
        Instant now = clock.instant();
        Optional<AccountToken> token = lookup(rawToken, AccountTokenPurpose.PASSWORD_RESET, now);
        Optional<UserAccount> owner = token.flatMap(t -> users.findByIdForUpdate(t.userId()))
                .filter(account -> account.status() == AccountStatus.ACTIVE);

        List<Map<String, String>> errors = new ArrayList<>();
        if (owner.isEmpty()) {
            errors.add(error("token", INVALID_LINK));
        }
        for (String violation : PasswordPolicy.violations(newPassword, owner.map(UserAccount::email).orElse(null))) {
            errors.add(error("newPassword", violation));
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", List.copyOf(errors)));
        }

        UserAccount account = owner.orElseThrow();
        users.updatePassword(account.id(), hasher.hash(newPassword), now);
        tokens.markUsed(token.orElseThrow().id(), now);
        tokens.revokeOutstanding(account.id(), AccountTokenPurpose.PASSWORD_RESET, now);
        refreshTokens.revokeAllOfUser(account.id(), now, REVOKE_RESET);
        noticeChanged(account.id(), now);
        audit(ACTION_RESET_COMPLETED, account.id(), Outcome.SUCCESS, null, origin);
    }

    /**
     * Cambio de contraseña con sesión iniciada. Exige la contraseña actual; si es errónea cuenta como intento fallido
     * (mismo bloqueo del inicio de sesión) para que un access token robado no sirva para adivinarla.
     *
     * <p>Devuelve el resultado en lugar de lanzar excepción cuando hay que conservar el contador de intentos.
     *
     * @throws DomainException {@code VALIDATION_FAILED} si la contraseña nueva no cumple la política
     */
    @Transactional
    public ChangeResult changePassword(UUID userId, String currentPassword, String newPassword, ClientOrigin origin) {
        Instant now = clock.instant();
        UserAccount account = users.findByIdForUpdate(userId)
                .filter(found -> found.status() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new DomainException(ErrorCode.AUTH_UNAUTHENTICATED, null));
        String current = currentPassword == null ? "" : currentPassword;

        if (account.isTemporarilyLockedAt(now)) {
            hasher.simulateVerification(current);
            audit(ACTION_CHANGE_DENIED, userId, Outcome.DENIED, "LOCKED", origin);
            return new ChangeResult.Locked(Math.max(1, java.time.Duration.between(now, account.lockedUntil()).toSeconds() + 1));
        }
        if (account.passwordHash() == null || !hasher.matches(current, account.passwordHash())) {
            LockoutPolicy.State state = authSettings.lockout().afterFailure(account, now);
            users.recordFailedLogin(userId, state.failedAttempts(), state.lockedUntil());
            audit(ACTION_CHANGE_DENIED, userId, Outcome.DENIED, "BAD_CURRENT_PASSWORD", origin);
            return new ChangeResult.WrongCurrentPassword();
        }

        List<Map<String, String>> errors = new ArrayList<>();
        for (String violation : PasswordPolicy.violations(newPassword, account.email())) {
            errors.add(error("newPassword", violation));
        }
        if (errors.isEmpty() && newPassword.equals(current)) {
            errors.add(error("newPassword", "La contraseña nueva debe ser distinta de la actual."));
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", List.copyOf(errors)));
        }

        users.updatePassword(userId, hasher.hash(newPassword), now);
        tokens.revokeOutstanding(userId, AccountTokenPurpose.PASSWORD_RESET, now);
        refreshTokens.revokeAllOfUser(userId, now, REVOKE_CHANGE);
        noticeChanged(userId, now);
        audit(ACTION_PASSWORD_CHANGED, userId, Outcome.SUCCESS, null, origin);
        return new ChangeResult.Changed();
    }

    private Optional<AccountToken> lookup(String rawToken, AccountTokenPurpose purpose, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByHashForUpdate(OpaqueToken.sha256Hex(rawToken.trim()))
                .filter(token -> token.purpose() == purpose && token.isUsableAt(now));
    }

    private void noticeChanged(UUID userId, Instant now) {
        queue.enqueue(NOTICE_CHANGED, List.of(userId), "Su contraseña fue cambiada", Map.of(), RELATED_ENTITY, userId,
                "changed:" + now.toEpochMilli());
    }

    private void audit(String action, UUID userId, Outcome outcome, String reason, ClientOrigin origin) {
        ClientOrigin from = origin == null ? ClientOrigin.UNKNOWN : origin;
        audit.write(AuditRecord.builder(action, RELATED_ENTITY)
                .actor(userId, null)
                .entity(userId)
                .outcome(outcome)
                .reason(reason)
                .origin(from.ip(), from.userAgent())
                .build());
    }

    private static java.net.InetAddress ip(ClientOrigin origin) {
        return origin == null ? null : origin.ip();
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of("field", field, "message", message);
    }
}
