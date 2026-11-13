package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.application.Fakes.FakeHasher;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryAccountTokens;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryRefreshTokens;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryUsers;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.MutableClock;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.RecordingQueue;
import co.edu.unipamplona.canchatrack.identity.application.PasswordRecoveryService.ChangeResult;
import co.edu.unipamplona.canchatrack.identity.domain.AccountStatus;
import co.edu.unipamplona.canchatrack.identity.domain.AccountTokenPurpose;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class PasswordRecoveryServiceTest {

    private static final String EMAIL = "ana@club.test";
    private static final String OLD = "Clave-Vieja-123";
    private static final String NEW = "Clave-Nueva-456";

    private final MutableClock clock = new MutableClock();
    private final InMemoryUsers users = new InMemoryUsers();
    private final InMemoryAccountTokens accountTokens = new InMemoryAccountTokens();
    private final InMemoryRefreshTokens refreshTokens = new InMemoryRefreshTokens();
    private final FakeHasher hasher = new FakeHasher();
    private final RecordingQueue queue = new RecordingQueue();
    private final RecordingAudit audit = new RecordingAudit();

    private PasswordRecoveryService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        AuthSettings auth = new AuthSettings(Duration.ofDays(7), Duration.ofDays(30),
                new LockoutPolicy(3, Duration.ofMinutes(15)), false);
        AccountSecuritySettings settings = new AccountSecuritySettings(Duration.ofMinutes(30), Duration.ofHours(48),
                "https://club.test", "/reset-password", "/verify-email", Duration.ofDays(7));
        service = new PasswordRecoveryService(users, accountTokens, refreshTokens, hasher, queue, audit, auth, settings, clock);
        userId = users.create(new UserAccountRepository.NewUser(EMAIL, "Ana Gómez", hasher.hash(OLD),
                java.util.EnumSet.of(co.edu.unipamplona.canchatrack.identity.domain.RoleCode.GUARDIAN), null, false)).id();
    }

    private String requestAndTakeToken() {
        service.requestReset(EMAIL, ClientOrigin.UNKNOWN);
        return queue.ofType("PASSWORD_RESET").get(queue.ofType("PASSWORD_RESET").size() - 1).payload().get("token");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorFields(DomainException e) {
        return ((List<Map<String, String>>) e.getExtensions().get("errors")).stream().map(m -> m.get("field")).toList();
    }

    private void openSession() {
        refreshTokens.insert(new RefreshTokenStore.NewRefreshToken(UUID.randomUUID(), userId, UUID.randomUUID(), "h".repeat(64),
                clock.instant(), clock.instant().plusSeconds(600), clock.instant().plusSeconds(6000), ClientOrigin.UNKNOWN));
    }

    // --- solicitud

    @Test
    void requestingAResetEmailsALinkAndStoresOnlyTheHash() {
        service.requestReset("  ANA@club.test ", ClientOrigin.UNKNOWN);

        assertThat(queue.ofType("PASSWORD_RESET")).hasSize(1);
        RecordingQueue.Notice notice = queue.ofType("PASSWORD_RESET").get(0);
        assertThat(notice.recipients()).containsExactly(userId);
        String token = notice.payload().get("token");
        assertThat(notice.payload().get("url")).isEqualTo("https://club.test/reset-password?token=" + token);
        assertThat(notice.payload()).containsEntry("validityMinutes", "30");
        assertThat(accountTokens.storesHashOnly(token)).isTrue();
        assertThat(accountTokens.expiresAtOf(token)).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        assertThat(audit.count("PASSWORD_RESET_REQUESTED")).isEqualTo(1);
    }

    @Test
    void anUnknownEmailGetsTheSameSilenceAndNothingIsSent() {
        service.requestReset("nadie@club.test", ClientOrigin.UNKNOWN);
        service.requestReset("no-es-un-correo", ClientOrigin.UNKNOWN);
        service.requestReset(null, ClientOrigin.UNKNOWN);

        assertThat(queue.notices).isEmpty();
        assertThat(accountTokens.size()).isZero();
        assertThat(audit.records).allSatisfy(r -> assertThat(r.reason()).isEqualTo("UNKNOWN_USER"));
    }

    @Test
    void disabledAccountsGetNoLink() {
        users.setStatus(userId, AccountStatus.DISABLED);

        service.requestReset(EMAIL, ClientOrigin.UNKNOWN);

        assertThat(queue.notices).isEmpty();
        assertThat(audit.records.get(0).reason()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
    }

    @Test
    void aNewRequestInvalidatesThePreviousLink() {
        String first = requestAndTakeToken();
        String second = requestAndTakeToken();

        assertThat(accountTokens.open(userId, AccountTokenPurpose.PASSWORD_RESET)).isEqualTo(1);
        assertThatThrownBy(() -> service.confirmReset(first, NEW, ClientOrigin.UNKNOWN)).isInstanceOf(DomainException.class);
        service.confirmReset(second, NEW, ClientOrigin.UNKNOWN);
        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(NEW));
    }

    // --- canje

    @Test
    void confirmingSetsThePasswordRevokesSessionsAndWarnsTheOwner() {
        openSession();
        users.recordFailedLogin(userId, 2, null);
        String token = requestAndTakeToken();

        service.confirmReset(token, NEW, ClientOrigin.UNKNOWN);

        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(NEW));
        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).isZero();
        assertThat(users.byEmail(EMAIL).isEmailVerified()).as("recibir el enlace demuestra el correo").isTrue();
        assertThat(refreshTokens.allRevoked(userId)).isTrue();
        assertThat(queue.ofType("PASSWORD_CHANGED")).hasSize(1);
        assertThat(queue.ofType("PASSWORD_CHANGED").get(0).payload()).isEmpty();
        assertThat(audit.count("PASSWORD_RESET_COMPLETED")).isEqualTo(1);
        assertThat(accountTokens.open(userId, AccountTokenPurpose.PASSWORD_RESET)).isZero();
    }

    @Test
    void aLinkWorksOnlyOnce() {
        String token = requestAndTakeToken();
        service.confirmReset(token, NEW, ClientOrigin.UNKNOWN);

        assertThatThrownBy(() -> service.confirmReset(token, "Otra-Clave-789", ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(errorFields(e)).containsExactly("token");
                });
        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(NEW));
    }

    @Test
    void anExpiredLinkIsRejected() {
        String token = requestAndTakeToken();
        clock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> service.confirmReset(token, NEW, ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(errorFields(e)).containsExactly("token"));
        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(OLD));
    }

    @Test
    void unknownBlankAndForeignPurposeTokensAreAllTheSameError() {
        String verification = new EmailVerificationService(users, accountTokens, queue, audit,
                new AccountSecuritySettings(Duration.ofMinutes(30), Duration.ofHours(48), "", "/r", "/v", Duration.ofDays(7)),
                clock).getClass().getSimpleName();
        assertThat(verification).isEqualTo("EmailVerificationService");
        EmailVerificationService mailer = new EmailVerificationService(users, accountTokens, queue, audit,
                new AccountSecuritySettings(Duration.ofMinutes(30), Duration.ofHours(48), "", "/r", "/v", Duration.ofDays(7)),
                clock);
        mailer.sendFor(userId, ClientOrigin.UNKNOWN);
        String verifyToken = queue.ofType("EMAIL_VERIFICATION").get(0).payload().get("token");

        for (String bad : new String[] {"no-existe", "", "   ", null, verifyToken}) {
            assertThatThrownBy(() -> service.confirmReset(bad, NEW, ClientOrigin.UNKNOWN))
                    .isInstanceOfSatisfying(DomainException.class, e -> assertThat(errorFields(e)).containsExactly("token"));
        }
        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(OLD));
    }

    @Test
    void aWeakPasswordIsReportedTogetherWithoutSpendingTheLink() {
        String token = requestAndTakeToken();

        assertThatThrownBy(() -> service.confirmReset(token, "corta", ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(errorFields(e)).contains("newPassword"));
        service.confirmReset(token, NEW, ClientOrigin.UNKNOWN); // el mismo enlace sigue sirviendo
        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(NEW));
    }

    @Test
    void aWeakPasswordWithABadLinkReportsBothFields() {
        assertThatThrownBy(() -> service.confirmReset("no-existe", "corta", ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(errorFields(e)).contains("token", "newPassword"));
    }

    @Test
    void theNewPasswordCannotBeTheEmail() {
        String token = requestAndTakeToken();

        assertThatThrownBy(() -> service.confirmReset(token, EMAIL, ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(errorFields(e)).contains("newPassword"));
    }

    @Test
    void aDisabledAccountCannotUseAnOutstandingLink() {
        String token = requestAndTakeToken();
        users.setStatus(userId, AccountStatus.DISABLED);

        assertThatThrownBy(() -> service.confirmReset(token, NEW, ClientOrigin.UNKNOWN)).isInstanceOf(DomainException.class);
        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(OLD));
    }

    // --- cambio autenticado

    @Test
    void changingThePasswordRevokesSessionsAndWarnsTheOwner() {
        openSession();

        ChangeResult result = service.changePassword(userId, OLD, NEW, ClientOrigin.UNKNOWN);

        assertThat(result).isInstanceOf(ChangeResult.Changed.class);
        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(NEW));
        assertThat(refreshTokens.allRevoked(userId)).isTrue();
        assertThat(queue.ofType("PASSWORD_CHANGED")).hasSize(1);
        assertThat(audit.count("PASSWORD_CHANGED")).isEqualTo(1);
    }

    @Test
    void changingThePasswordInvalidatesAnOutstandingResetLink() {
        String token = requestAndTakeToken();

        service.changePassword(userId, OLD, NEW, ClientOrigin.UNKNOWN);

        assertThatThrownBy(() -> service.confirmReset(token, "Otra-Clave-789", ClientOrigin.UNKNOWN))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aWrongCurrentPasswordCountsAsAFailedAttemptAndEventuallyLocks() {
        assertThat(service.changePassword(userId, "mala-1", NEW, ClientOrigin.UNKNOWN))
                .isInstanceOf(ChangeResult.WrongCurrentPassword.class);
        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).isEqualTo(1);
        service.changePassword(userId, "mala-2", NEW, ClientOrigin.UNKNOWN);
        service.changePassword(userId, "mala-3", NEW, ClientOrigin.UNKNOWN);

        ChangeResult locked = service.changePassword(userId, OLD, NEW, ClientOrigin.UNKNOWN);

        assertThat(locked).isInstanceOfSatisfying(ChangeResult.Locked.class,
                l -> assertThat(l.retryAfterSeconds()).isEqualTo(15 * 60 + 1));
        assertThat(users.byEmail(EMAIL).passwordHash()).as("ni con la clave correcta mientras esté bloqueada")
                .isEqualTo(hasher.hash(OLD));
        assertThat(audit.count("PASSWORD_CHANGE_DENIED")).isEqualTo(4);
        assertThat(queue.notices).isEmpty();
    }

    @Test
    void theNewPasswordMustFollowThePolicyAndDifferFromTheCurrentOne() {
        assertThatThrownBy(() -> service.changePassword(userId, OLD, "corta", ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(errorFields(e)).contains("newPassword"));
        assertThatThrownBy(() -> service.changePassword(userId, OLD, OLD, ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(errorFields(e)).containsExactly("newPassword"));
        assertThat(users.byEmail(EMAIL).passwordHash()).isEqualTo(hasher.hash(OLD));
        assertThat(queue.notices).isEmpty();
    }

    @Test
    void aMissingOrDisabledAccountIsUnauthenticated() {
        assertThatThrownBy(() -> service.changePassword(UUID.randomUUID(), OLD, NEW, ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED));
        users.setStatus(userId, AccountStatus.DISABLED);
        assertThatThrownBy(() -> service.changePassword(userId, OLD, NEW, ClientOrigin.UNKNOWN))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED));
    }
}
