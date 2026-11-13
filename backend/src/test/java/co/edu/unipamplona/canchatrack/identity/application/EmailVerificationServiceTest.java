package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryAccountTokens;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryUsers;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.MutableClock;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.RecordingQueue;
import co.edu.unipamplona.canchatrack.identity.domain.AccountStatus;
import co.edu.unipamplona.canchatrack.identity.domain.AccountTokenPurpose;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class EmailVerificationServiceTest {

    private static final String EMAIL = "ana@club.test";

    private final MutableClock clock = new MutableClock();
    private final InMemoryUsers users = new InMemoryUsers();
    private final InMemoryAccountTokens tokens = new InMemoryAccountTokens();
    private final RecordingQueue queue = new RecordingQueue();
    private final RecordingAudit audit = new RecordingAudit();

    private EmailVerificationService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(users, tokens, queue, audit,
                new AccountSecuritySettings(Duration.ofMinutes(30), Duration.ofHours(48), "", "/reset-password", "/verify-email",
                        Duration.ofDays(7)), clock);
        userId = users.create(new UserAccountRepository.NewUser(EMAIL, "Ana Gómez", "hash:x", EnumSet.of(RoleCode.GUARDIAN),
                null, false)).id();
    }

    private String lastToken() {
        List<RecordingQueue.Notice> sent = queue.ofType("EMAIL_VERIFICATION");
        return sent.get(sent.size() - 1).payload().get("token");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorFields(DomainException e) {
        return ((List<Map<String, String>>) e.getExtensions().get("errors")).stream().map(m -> m.get("field")).toList();
    }

    @Test
    void sendingStoresTheHashAndMailsTheCodeWhenThereIsNoFrontAddress() {
        service.sendFor(userId, ClientOrigin.UNKNOWN);

        RecordingQueue.Notice notice = queue.ofType("EMAIL_VERIFICATION").get(0);
        assertThat(notice.payload()).doesNotContainKey("url").containsEntry("validityHours", "48");
        assertThat(tokens.storesHashOnly(lastToken())).isTrue();
        assertThat(tokens.expiresAtOf(lastToken())).isEqualTo(clock.instant().plus(Duration.ofHours(48)));
    }

    @Test
    void confirmingMarksTheEmailVerifiedAndSpendsTheToken() {
        service.sendFor(userId, ClientOrigin.UNKNOWN);
        String token = lastToken();

        service.confirm("  " + token + " ", ClientOrigin.UNKNOWN);

        assertThat(users.byEmail(EMAIL).isEmailVerified()).isTrue();
        assertThat(tokens.open(userId, AccountTokenPurpose.EMAIL_VERIFICATION)).isZero();
        assertThat(audit.count("EMAIL_VERIFIED")).isEqualTo(1);
        assertThatThrownBy(() -> service.confirm(token, ClientOrigin.UNKNOWN)).isInstanceOf(DomainException.class);
    }

    @Test
    void anExpiredOrUnknownTokenIsAlwaysTheSameError() {
        service.sendFor(userId, ClientOrigin.UNKNOWN);
        String token = lastToken();
        clock.advance(Duration.ofHours(49));

        for (String bad : new String[] {token, "no-existe", "", null}) {
            assertThatThrownBy(() -> service.confirm(bad, ClientOrigin.UNKNOWN))
                    .isInstanceOfSatisfying(DomainException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(errorFields(e)).containsExactly("token");
                    });
        }
        assertThat(users.byEmail(EMAIL).isEmailVerified()).isFalse();
    }

    @Test
    void resendingReplacesThePreviousLink() {
        service.sendFor(userId, ClientOrigin.UNKNOWN);
        String first = lastToken();

        service.resend(" ANA@club.test ", ClientOrigin.UNKNOWN);
        String second = lastToken();

        assertThat(second).isNotEqualTo(first);
        assertThat(tokens.open(userId, AccountTokenPurpose.EMAIL_VERIFICATION)).isEqualTo(1);
        assertThatThrownBy(() -> service.confirm(first, ClientOrigin.UNKNOWN)).isInstanceOf(DomainException.class);
        service.confirm(second, ClientOrigin.UNKNOWN);
        assertThat(users.byEmail(EMAIL).isEmailVerified()).isTrue();
    }

    @Test
    void resendingSaysNothingForUnknownVerifiedOrDisabledAccounts() {
        service.resend("nadie@club.test", ClientOrigin.UNKNOWN);
        service.resend("no-es-un-correo", ClientOrigin.UNKNOWN);
        service.resend(null, ClientOrigin.UNKNOWN);
        users.markEmailVerified(userId, clock.instant());
        service.resend(EMAIL, ClientOrigin.UNKNOWN);
        UUID other = users.create(new UserAccountRepository.NewUser("otro@club.test", "Otro", "hash:x",
                EnumSet.of(RoleCode.GUARDIAN), null, false)).id();
        users.setStatus(other, AccountStatus.DISABLED);
        service.resend("otro@club.test", ClientOrigin.UNKNOWN);

        assertThat(queue.notices).isEmpty();
        assertThat(tokens.size()).isZero();
    }

    @Test
    void confirmingAnAlreadyVerifiedAccountKeepsTheOriginalDate() {
        service.sendFor(userId, ClientOrigin.UNKNOWN);
        String token = lastToken();
        java.time.Instant early = clock.instant().minusSeconds(86_400);
        users.markEmailVerified(userId, early);

        service.confirm(token, ClientOrigin.UNKNOWN);

        assertThat(users.byEmail(EMAIL).emailVerifiedAt()).isEqualTo(early);
    }

    @Test
    void aDisabledAccountCannotConfirm() {
        service.sendFor(userId, ClientOrigin.UNKNOWN);
        String token = lastToken();
        users.setStatus(userId, AccountStatus.DISABLED);

        assertThatThrownBy(() -> service.confirm(token, ClientOrigin.UNKNOWN)).isInstanceOf(DomainException.class);
        assertThat(users.byEmail(EMAIL).isEmailVerified()).isFalse();
    }
}
