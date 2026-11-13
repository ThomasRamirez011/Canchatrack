package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Clock;
import java.time.Instant;
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
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Confirmación del correo del autorregistro: emite un enlace de un solo uso, lo reenvía a petición y lo canjea. Pedir un
 * reenvío responde igual exista o no la cuenta.
 */
@Service
public class EmailVerificationService {

    static final String ACTION_REQUESTED = "EMAIL_VERIFICATION_REQUESTED";
    static final String ACTION_VERIFIED = "EMAIL_VERIFIED";
    static final String NOTICE_VERIFY = "EMAIL_VERIFICATION";
    static final String RELATED_ENTITY = "user";
    private static final String INVALID_LINK = "El enlace no es válido o ya venció.";

    private final UserAccountRepository users;
    private final AccountTokenStore tokens;
    private final NotificationQueue queue;
    private final AuditWriter audit;
    private final AccountSecuritySettings settings;
    private final Clock clock;

    public EmailVerificationService(UserAccountRepository users, AccountTokenStore tokens, NotificationQueue queue,
                                    AuditWriter audit, AccountSecuritySettings settings, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.queue = queue;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /** Emite el enlace y encola el correo para una cuenta recién creada (se llama dentro de la transacción del alta). */
    @Transactional
    public void sendFor(UUID userId, ClientOrigin origin) {
        Instant now = clock.instant();
        tokens.revokeOutstanding(userId, AccountTokenPurpose.EMAIL_VERIFICATION, now);
        String raw = OpaqueToken.generate();
        UUID tokenId = UUID.randomUUID();
        tokens.insert(new AccountTokenStore.NewAccountToken(tokenId, userId, AccountTokenPurpose.EMAIL_VERIFICATION,
                OpaqueToken.sha256Hex(raw), now, now.plus(settings.verificationTtl()),
                origin == null ? null : origin.ip()));

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("token", raw);
        String url = settings.verifyUrl(raw);
        if (!url.isEmpty()) {
            payload.put("url", url);
        }
        payload.put("validityHours", Long.toString(Math.max(1, settings.verificationTtl().toHours())));
        queue.enqueue(NOTICE_VERIFY, List.of(userId), "Confirme su correo", payload, RELATED_ENTITY, tokenId, "verify");
        audit(ACTION_REQUESTED, userId, Outcome.SUCCESS, null, origin);
    }

    /** Reenvía el enlace si la cuenta existe, está activa y aún no confirmó; en cualquier otro caso no hace nada. */
    @Transactional
    public void resend(String email, ClientOrigin origin) {
        String normalized = Emails.normalize(email);
        Optional<UserAccount> found = Emails.isValid(normalized) ? users.findByEmailForUpdate(normalized) : Optional.empty();
        if (found.isEmpty() || found.get().status() != AccountStatus.ACTIVE || found.get().isEmailVerified()) {
            audit(ACTION_REQUESTED, found.map(UserAccount::id).orElse(null), Outcome.DENIED,
                    found.isEmpty() ? "UNKNOWN_USER" : "NOT_APPLICABLE", origin);
            return;
        }
        sendFor(found.get().id(), origin);
    }

    /** Confirma el correo con el token recibido. Un token inexistente, usado, revocado o vencido produce el mismo error. */
    @Transactional
    public void confirm(String rawToken, ClientOrigin origin) {
        Instant now = clock.instant();
        Optional<AccountToken> token = rawToken == null || rawToken.isBlank()
                ? Optional.empty()
                : tokens.findByHashForUpdate(OpaqueToken.sha256Hex(rawToken.trim()))
                        .filter(t -> t.purpose() == AccountTokenPurpose.EMAIL_VERIFICATION && t.isUsableAt(now));
        Optional<UserAccount> owner = token.flatMap(t -> users.findByIdForUpdate(t.userId()))
                .filter(account -> account.status() == AccountStatus.ACTIVE);
        if (owner.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, null,
                    Map.of("errors", List.of(Map.of("field", "token", "message", INVALID_LINK))));
        }
        UserAccount account = owner.get();
        users.markEmailVerified(account.id(), now);
        tokens.markUsed(token.orElseThrow().id(), now);
        tokens.revokeOutstanding(account.id(), AccountTokenPurpose.EMAIL_VERIFICATION, now);
        audit(ACTION_VERIFIED, account.id(), Outcome.SUCCESS, null, origin);
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
}
