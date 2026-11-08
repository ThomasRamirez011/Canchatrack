package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditRecord.Outcome;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.domain.Emails;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.RefreshToken;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;

/**
 * Inicio, renovación y cierre de sesión.
 *
 * <p>Los rechazos se devuelven como {@link SessionResult.Rejected} y no como excepción para que la transacción se
 * confirme: el contador de intentos fallidos, el bloqueo, la revocación de la familia por reutilización y la auditoría
 * deben persistir aunque la respuesta sea 401.
 */
@Service
public class AuthenticationService {

    static final String ACTION_LOGIN_SUCCESS = "AUTH_LOGIN_SUCCESS";
    static final String ACTION_LOGIN_FAILED = "AUTH_LOGIN_FAILED";
    static final String ACTION_REFRESH_SUCCESS = "AUTH_REFRESH_SUCCESS";
    static final String ACTION_REFRESH_DENIED = "AUTH_REFRESH_DENIED";
    static final String ACTION_REFRESH_REUSE = "AUTH_REFRESH_REUSE_DETECTED";
    static final String ACTION_LOGOUT = "AUTH_LOGOUT";

    private final UserAccountRepository users;
    private final RefreshTokenStore refreshTokens;
    private final PasswordHasher hasher;
    private final AccessTokenIssuer accessTokens;
    private final AuditWriter audit;
    private final AuthSettings settings;
    private final Clock clock;

    public AuthenticationService(
            UserAccountRepository users,
            RefreshTokenStore refreshTokens,
            PasswordHasher hasher,
            AccessTokenIssuer accessTokens,
            AuditWriter audit,
            AuthSettings settings,
            Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.accessTokens = accessTokens;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public SessionResult login(String email, String password, ClientOrigin origin) {
        Instant now = clock.instant();
        String normalizedEmail = Emails.normalize(email);
        String rawPassword = password == null ? "" : password;

        Optional<UserAccount> found = users.findByEmailForUpdate(normalizedEmail);
        if (found.isEmpty()) {
            hasher.simulateVerification(rawPassword);
            record(ACTION_LOGIN_FAILED, null, Outcome.DENIED, "UNKNOWN_USER", origin);
            return new SessionResult.Rejected();
        }

        UserAccount account = found.get();
        if (!account.canAuthenticate(now)) {
            // Mientras la cuenta está bloqueada o inhabilitada no se evalúa la contraseña ni se cuentan intentos:
            // así un atacante no puede prolongar el bloqueo de la víctima.
            hasher.simulateVerification(rawPassword);
            String reason = account.isTemporarilyLockedAt(now) ? "LOCKED" : "ACCOUNT_NOT_ACTIVE";
            record(ACTION_LOGIN_FAILED, account.id(), Outcome.DENIED, reason, origin);
            return new SessionResult.Rejected();
        }

        if (!hasher.matches(rawPassword, account.passwordHash())) {
            LockoutPolicy.State state = settings.lockout().afterFailure(account, now);
            users.recordFailedLogin(account.id(), state.failedAttempts(), state.lockedUntil());
            record(ACTION_LOGIN_FAILED, account.id(), Outcome.DENIED,
                    state.locked() ? "BAD_CREDENTIALS_LOCKED_NOW" : "BAD_CREDENTIALS", origin);
            return new SessionResult.Rejected();
        }

        users.recordSuccessfulLogin(account.id(), now);
        TokenPair tokens = openSession(account, UUID.randomUUID(), now.plus(settings.refreshAbsoluteTtl()), now, origin);
        record(ACTION_LOGIN_SUCCESS, account.id(), Outcome.SUCCESS, null, origin);
        return new SessionResult.Opened(tokens, UserView.from(account));
    }

    /**
     * Canjea un refresh token por un par nuevo (rotación). Presentar un token ya canjeado revoca toda la sesión:
     * o lo usa un atacante con una copia robada o lo usa el cliente legítimo tras haberlo perdido; en ambos casos la
     * sesión ya no es de fiar y se exige iniciar sesión de nuevo.
     */
    @Transactional
    public SessionResult refresh(String refreshToken, ClientOrigin origin) {
        Instant now = clock.instant();
        if (refreshToken == null || refreshToken.isBlank()) {
            return new SessionResult.Rejected();
        }

        Optional<RefreshToken> found = refreshTokens.findByHashForUpdate(OpaqueToken.sha256Hex(refreshToken));
        if (found.isEmpty()) {
            return new SessionResult.Rejected();
        }

        RefreshToken token = found.get();
        switch (token.statusAt(now)) {
            case USED -> {
                refreshTokens.revokeFamily(token.familyId(), now, "REUSE_DETECTED");
                record(ACTION_REFRESH_REUSE, token.userId(), Outcome.DENIED, "REUSE_DETECTED", origin);
                return new SessionResult.Rejected();
            }
            case REVOKED, EXPIRED -> {
                return new SessionResult.Rejected();
            }
            case ACTIVE -> {
                // continúa
            }
        }

        Optional<UserAccount> owner = users.findById(token.userId());
        if (owner.isEmpty() || !owner.get().canAuthenticate(now)) {
            refreshTokens.revokeFamily(token.familyId(), now, "ACCOUNT_NOT_ACTIVE");
            record(ACTION_REFRESH_DENIED, token.userId(), Outcome.DENIED, "ACCOUNT_NOT_ACTIVE", origin);
            return new SessionResult.Rejected();
        }

        UserAccount account = owner.get();
        refreshTokens.markUsed(token.id(), now);
        TokenPair tokens = openSession(account, token.familyId(), token.familyExpiresAt(), now, origin);
        record(ACTION_REFRESH_SUCCESS, account.id(), Outcome.SUCCESS, null, origin);
        return new SessionResult.Opened(tokens, UserView.from(account));
    }

    /** Cierra la sesión del refresh token dado. Idempotente y silencioso: no revela si el token existía. */
    @Transactional
    public void logout(String refreshToken, ClientOrigin origin) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        refreshTokens.findByHashForUpdate(OpaqueToken.sha256Hex(refreshToken)).ifPresent(token -> {
            if (token.revokedAt() == null) {
                refreshTokens.revokeFamily(token.familyId(), now, "LOGOUT");
                record(ACTION_LOGOUT, token.userId(), Outcome.SUCCESS, null, origin);
            }
        });
    }

    private TokenPair openSession(UserAccount account, UUID familyId, Instant familyExpiresAt, Instant now,
                                  ClientOrigin origin) {
        String rawRefresh = OpaqueToken.generate();
        Instant refreshExpiresAt = now.plus(settings.refreshTtl());
        if (refreshExpiresAt.isAfter(familyExpiresAt)) {
            refreshExpiresAt = familyExpiresAt;
        }
        refreshTokens.insert(new RefreshTokenStore.NewRefreshToken(
                UUID.randomUUID(), account.id(), familyId, OpaqueToken.sha256Hex(rawRefresh),
                now, refreshExpiresAt, familyExpiresAt, origin));
        AccessTokenIssuer.IssuedAccessToken access = accessTokens.issue(account.id(), account.roles(), now);
        return new TokenPair(access.token(), access.expiresAt(), rawRefresh, refreshExpiresAt);
    }

    private void record(String action, UUID userId, Outcome outcome, String reason, ClientOrigin origin) {
        ClientOrigin from = origin == null ? ClientOrigin.UNKNOWN : origin;
        audit.write(AuditRecord.builder(action, "user")
                .actor(userId, null)
                .entity(userId)
                .outcome(outcome)
                .reason(reason)
                .origin(from.ip(), from.userAgent())
                .build());
    }
}
