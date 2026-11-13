package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditRecord.Outcome;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.domain.Base32;
import co.edu.unipamplona.canchatrack.identity.domain.BackupCodes;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.MfaStep;
import co.edu.unipamplona.canchatrack.identity.domain.Totp;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Segundo factor: configuración, verificación del código y administración.
 *
 * <p>Los rechazos se devuelven como valor y no como excepción para que la transacción se confirme: el contador de
 * intentos del desafío, el bloqueo de la cuenta y la auditoría deben persistir aunque la respuesta sea 401. Un código
 * equivocado cuenta para el mismo bloqueo que una contraseña equivocada, de modo que quien conoce la contraseña no
 * puede adivinar el código con tranquilidad (5 fallos y la cuenta se bloquea 15 minutos).
 */
@Service
public class MfaAuthenticationService {

    static final String ACTION_ENROLLMENT_STARTED = "MFA_ENROLLMENT_STARTED";
    static final String ACTION_ENROLLED = "MFA_ENROLLED";
    static final String ACTION_VERIFIED = "MFA_VERIFIED";
    static final String ACTION_VERIFY_FAILED = "MFA_VERIFY_FAILED";
    static final String ACTION_CODES_REGENERATED = "MFA_BACKUP_CODES_REGENERATED";
    static final String ACTION_RESET = "MFA_RESET";
    static final String REVOKE_RESET = "MFA_RESET";

    /** Resultado de completar el inicio de sesión con el segundo factor. */
    public sealed interface MfaOutcome {

        /** @param backupCodes los códigos de respaldo recién creados (solo al terminar la configuración) */
        record Opened(SessionResult.Opened session, List<String> backupCodes) implements MfaOutcome {
        }

        record Rejected() implements MfaOutcome {
        }
    }

    /** Secreto que el usuario carga en su aplicación autenticadora. */
    public record EnrollmentSecret(String secret, String otpauthUri, String issuer, String accountName) {

        @Override
        public String toString() {
            return "EnrollmentSecret[accountName=" + accountName + ", secret=***]";
        }
    }

    public record Status(boolean required, boolean enrolled, int backupCodesRemaining) {
    }

    public sealed interface RegenerateResult {

        record Regenerated(List<String> backupCodes) implements RegenerateResult {
        }

        record InvalidCode() implements RegenerateResult {
        }

        record Locked(long retryAfterSeconds) implements RegenerateResult {
        }

        record NotEnrolled() implements RegenerateResult {
        }
    }

    private record Context(MfaStore.Challenge challenge, UserAccount account) {
    }

    private final MfaStore store;
    private final MfaCrypto crypto;
    private final MfaService mfa;
    private final UserAccountRepository users;
    private final RefreshTokenStore refreshTokens;
    private final AuthenticationService authentication;
    private final AuthSettings authSettings;
    private final AuditWriter audit;
    private final Clock clock;

    public MfaAuthenticationService(MfaStore store, MfaCrypto crypto, MfaService mfa, UserAccountRepository users,
                                    RefreshTokenStore refreshTokens, AuthenticationService authentication,
                                    AuthSettings authSettings, AuditWriter audit, Clock clock) {
        this.store = store;
        this.crypto = crypto;
        this.mfa = mfa;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.authentication = authentication;
        this.authSettings = authSettings;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Genera el secreto del cuenta que aún no tiene factor. Se puede pedir de nuevo mientras el factor no esté
     * confirmado (reemplaza al anterior); la respuesta es vacía para cualquier desafío inservible.
     */
    @Transactional
    public Optional<EnrollmentSecret> beginEnrollment(String rawToken, ClientOrigin origin) {
        Instant now = clock.instant();
        Optional<Context> opened = open(rawToken, MfaStep.ENROLL, now);
        if (opened.isEmpty()) {
            return Optional.empty();
        }
        UserAccount account = opened.get().account();
        byte[] secret = Totp.newSecret();
        if (!store.savePendingFactor(account.id(), crypto.seal(account.id(), secret), now)) {
            return Optional.empty();
        }
        record(ACTION_ENROLLMENT_STARTED, account.id(), Outcome.SUCCESS, null, origin);
        String issuer = mfa.settings().issuer();
        return Optional.of(new EnrollmentSecret(Base32.encode(secret), Totp.otpauthUri(issuer, account.email(), secret),
                issuer, account.email()));
    }

    /** Termina la configuración: el código demuestra que la aplicación quedó bien enlazada. Abre la sesión. */
    @Transactional
    public MfaOutcome confirmEnrollment(String rawToken, String code, ClientOrigin origin) {
        Instant now = clock.instant();
        Optional<Context> opened = open(rawToken, MfaStep.ENROLL, now);
        if (opened.isEmpty()) {
            return new MfaOutcome.Rejected();
        }
        Context context = opened.get();
        UserAccount account = context.account();
        Optional<MfaStore.Factor> factor = store.findFactorForUpdate(account.id()).filter(f -> !f.active());
        if (factor.isEmpty()) {
            return reject(context, now, origin, "NO_PENDING_FACTOR");
        }
        OptionalLong step = checkTotp(account.id(), factor.get(), code, now, null);
        if (step.isEmpty()) {
            return reject(context, now, origin, "BAD_CODE");
        }
        store.activateFactor(account.id(), step.getAsLong(), now);
        List<String> codes = issueBackupCodes(account.id(), now);
        store.consumeChallenge(context.challenge().id(), now);
        SessionResult.Opened session = authentication.establishSession(account, origin);
        record(ACTION_ENROLLED, account.id(), Outcome.SUCCESS, null, origin);
        return new MfaOutcome.Opened(session, codes);
    }

    /** Verifica el código (TOTP o de respaldo) de un inicio de sesión en curso y abre la sesión. */
    @Transactional
    public MfaOutcome verify(String rawToken, String code, ClientOrigin origin) {
        Instant now = clock.instant();
        Optional<Context> opened = open(rawToken, MfaStep.VERIFY, now);
        if (opened.isEmpty()) {
            return new MfaOutcome.Rejected();
        }
        Context context = opened.get();
        UserAccount account = context.account();
        Optional<MfaStore.Factor> factor = store.findFactorForUpdate(account.id()).filter(MfaStore.Factor::active);
        if (factor.isEmpty()) {
            return reject(context, now, origin, "NO_ACTIVE_FACTOR");
        }
        String submitted = code == null ? "" : code.trim();
        String method;
        if (isTotpShaped(submitted)) {
            OptionalLong step = checkTotp(account.id(), factor.get(), submitted, now, factor.get().lastUsedStep());
            if (step.isEmpty()) {
                return reject(context, now, origin, "BAD_CODE");
            }
            store.recordUsedStep(account.id(), step.getAsLong());
            method = "TOTP";
        } else if (BackupCodes.looksLikeBackupCode(submitted)
                && store.consumeBackupCode(account.id(),
                        crypto.fingerprint(account.id(), BackupCodes.normalize(submitted)), now)) {
            method = "BACKUP_CODE";
        } else {
            return reject(context, now, origin, "BAD_CODE");
        }
        store.consumeChallenge(context.challenge().id(), now);
        SessionResult.Opened session = authentication.establishSession(account, origin);
        record(ACTION_VERIFIED, account.id(), Outcome.SUCCESS, method, origin);
        return new MfaOutcome.Opened(session, List.of());
    }

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        boolean required = users.findById(userId).map(mfa::isRequiredFor).orElse(false);
        boolean enrolled = mfa.hasActiveFactor(userId);
        return new Status(required, enrolled, enrolled ? store.countUnusedBackupCodes(userId) : 0);
    }

    /** Genera otros códigos de respaldo (los anteriores dejan de servir). Exige un código TOTP vigente. */
    @Transactional
    public RegenerateResult regenerateBackupCodes(UUID userId, String code, ClientOrigin origin) {
        Instant now = clock.instant();
        Optional<UserAccount> found = users.findByIdForUpdate(userId);
        if (found.isEmpty()) {
            return new RegenerateResult.NotEnrolled();
        }
        UserAccount account = found.get();
        if (account.isTemporarilyLockedAt(now)) {
            return new RegenerateResult.Locked(Math.max(1, Duration.between(now, account.lockedUntil()).toSeconds()));
        }
        Optional<MfaStore.Factor> factor = store.findFactorForUpdate(userId).filter(MfaStore.Factor::active);
        if (factor.isEmpty()) {
            return new RegenerateResult.NotEnrolled();
        }
        OptionalLong step = checkTotp(userId, factor.get(), code == null ? "" : code.trim(), now,
                factor.get().lastUsedStep());
        if (step.isEmpty()) {
            LockoutPolicy.State state = authSettings.lockout().afterFailure(account, now);
            users.recordFailedLogin(userId, state.failedAttempts(), state.lockedUntil());
            record(ACTION_VERIFY_FAILED, userId, Outcome.DENIED, "BAD_CODE_REGENERATE", origin);
            return new RegenerateResult.InvalidCode();
        }
        store.recordUsedStep(userId, step.getAsLong());
        List<String> codes = issueBackupCodes(userId, now);
        record(ACTION_CODES_REGENERATED, userId, Outcome.SUCCESS, null, origin);
        return new RegenerateResult.Regenerated(codes);
    }

    /**
     * La coordinación reinicia el segundo factor de otra persona que perdió el teléfono y los códigos: se borra el
     * factor, se cierran todas sus sesiones y en su próximo inicio de sesión tendrá que configurarlo de nuevo. Nadie
     * puede reiniciar el suyo: así un access token robado no sirve para quitarse el factor.
     */
    @Transactional
    public void reset(UUID targetUserId, UUID actorId, String actorRole, ClientOrigin origin) {
        if (targetUserId.equals(actorId)) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "No puede reiniciar su propio segundo factor; pídalo a otro coordinador.");
        }
        Instant now = clock.instant();
        if (users.findByIdForUpdate(targetUserId).isEmpty()) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, null);
        }
        store.deleteFactorAndCodes(targetUserId);
        store.consumeOpenChallenges(targetUserId, now);
        refreshTokens.revokeAllOfUser(targetUserId, now, REVOKE_RESET);
        ClientOrigin from = origin == null ? ClientOrigin.UNKNOWN : origin;
        audit.write(AuditRecord.builder(ACTION_RESET, "user")
                .actor(actorId, actorRole)
                .entity(targetUserId)
                .outcome(Outcome.SUCCESS)
                .origin(from.ip(), from.userAgent())
                .build());
    }

    // --- interno

    /** Carga un desafío utilizable del tipo pedido y la cuenta a la que pertenece. */
    private Optional<Context> open(String rawToken, MfaStep purpose, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Optional<MfaStore.Challenge> found = store.findChallengeForUpdate(OpaqueToken.sha256Hex(rawToken.trim()));
        if (found.isEmpty() || !found.get().usableAt(now) || found.get().purpose() != purpose) {
            return Optional.empty();
        }
        Optional<UserAccount> account = users.findByIdForUpdate(found.get().userId());
        if (account.isEmpty() || !account.get().canAuthenticate(now)) {
            return Optional.empty();
        }
        return Optional.of(new Context(found.get(), account.get()));
    }

    private OptionalLong checkTotp(UUID userId, MfaStore.Factor factor, String code, Instant now, Long lastUsedStep) {
        byte[] secret = crypto.open(userId, factor.sealedSecret());
        return Totp.verify(secret, code, now, mfa.settings().window(), lastUsedStep);
    }

    private static boolean isTotpShaped(String code) {
        return code.length() == Totp.DIGITS && code.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private List<String> issueBackupCodes(UUID userId, Instant now) {
        List<String> codes = BackupCodes.generate(mfa.settings().backupCodeCount());
        store.replaceBackupCodes(userId,
                codes.stream().map(c -> crypto.fingerprint(userId, BackupCodes.normalize(c))).toList(), now);
        return codes;
    }

    private MfaOutcome reject(Context context, Instant now, ClientOrigin origin, String reason) {
        UserAccount account = context.account();
        store.recordChallengeAttempt(context.challenge().id());
        LockoutPolicy.State state = authSettings.lockout().afterFailure(account, now);
        users.recordFailedLogin(account.id(), state.failedAttempts(), state.lockedUntil());
        if (state.locked() || context.challenge().attempts() + 1 >= authSettings.lockout().maxAttempts()) {
            store.consumeChallenge(context.challenge().id(), now);
        }
        record(ACTION_VERIFY_FAILED, account.id(), Outcome.DENIED, reason, origin);
        return new MfaOutcome.Rejected();
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
