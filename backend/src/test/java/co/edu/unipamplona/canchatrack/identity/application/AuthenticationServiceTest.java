package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.application.Fakes.FakeHasher;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.FakeIssuer;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryRefreshTokens;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryUsers;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.MutableClock;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.identity.domain.AccountStatus;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;

class AuthenticationServiceTest {

    private static final String EMAIL = "coach@club.test";
    private static final String PASSWORD = "Entrenador-2026";

    private final MutableClock clock = new MutableClock();
    private final InMemoryUsers users = new InMemoryUsers();
    private final InMemoryRefreshTokens refreshTokens = new InMemoryRefreshTokens();
    private final FakeHasher hasher = new FakeHasher();
    private final RecordingAudit audit = new RecordingAudit();

    private AuthenticationService auth;
    private UUID userId;

    @BeforeEach
    void setUp() {
        AuthSettings settings = new AuthSettings(Duration.ofDays(7), Duration.ofDays(30),
                new LockoutPolicy(5, Duration.ofMinutes(15)));
        auth = new AuthenticationService(users, refreshTokens, hasher, new FakeIssuer(), audit, settings, clock);
        userId = new UserAdministrationService(users, hasher, audit)
                .createStaffUser(new CreateUserCommand(EMAIL, "Entrenador Uno", PASSWORD, List.of("COACH")),
                        UUID.randomUUID(), "COORDINATOR", ClientOrigin.UNKNOWN)
                .id();
    }

    private SessionResult.Opened loginOk() {
        SessionResult result = auth.login(EMAIL, PASSWORD, ClientOrigin.UNKNOWN);
        assertThat(result).isInstanceOf(SessionResult.Opened.class);
        return (SessionResult.Opened) result;
    }

    // --- login

    @Test
    void successfulLoginIssuesTokensAndIsAudited() {
        SessionResult.Opened opened = (SessionResult.Opened) auth.login("  COACH@Club.test ", PASSWORD, null);

        assertThat(opened.user().roles()).containsExactly("COACH");
        assertThat(opened.tokens().accessToken()).isNotBlank();
        assertThat(opened.tokens().refreshToken()).isNotBlank();
        assertThat(refreshTokens.storesHashOnly(opened.tokens().refreshToken())).isTrue();
        assertThat(audit.count("AUTH_LOGIN_SUCCESS")).isEqualTo(1);
    }

    @Test
    void unknownUserIsRejectedAfterSimulatingAVerification() {
        SessionResult result = auth.login("nadie@club.test", "lo-que-sea-1234", null);

        assertThat(result).isInstanceOf(SessionResult.Rejected.class);
        assertThat(hasher.simulated).isEqualTo(1);
        assertThat(audit.count("AUTH_LOGIN_FAILED")).isEqualTo(1);
    }

    @Test
    void wrongPasswordIsRejectedAndCounted() {
        assertThat(auth.login(EMAIL, "otra-clave-1234", null)).isInstanceOf(SessionResult.Rejected.class);

        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void fifthFailureLocksTheAccountAndEvenTheRightPasswordIsRefusedDuringTheLock() {
        for (int i = 0; i < 5; i++) {
            auth.login(EMAIL, "otra-clave-1234", null);
        }
        UserAccount locked = users.byEmail(EMAIL);
        assertThat(locked.lockedUntil()).isNotNull();

        assertThat(auth.login(EMAIL, PASSWORD, null)).isInstanceOf(SessionResult.Rejected.class);
        assertThat(users.byEmail(EMAIL).lockedUntil()).as("el bloqueo no se prolonga").isEqualTo(locked.lockedUntil());
        assertThat(users.byEmail(EMAIL).failedLoginAttempts()).as("no se cuentan intentos durante el bloqueo").isZero();
    }

    @Test
    void afterTheLockExpiresTheRightPasswordWorksAndClearsTheState() {
        for (int i = 0; i < 5; i++) {
            auth.login(EMAIL, "otra-clave-1234", null);
        }
        clock.advance(Duration.ofMinutes(16));

        assertThat(auth.login(EMAIL, PASSWORD, null)).isInstanceOf(SessionResult.Opened.class);
        assertThat(users.byEmail(EMAIL).lockedUntil()).isNull();
    }

    @Test
    void disabledAccountsCannotLogIn() {
        users.setStatus(userId, AccountStatus.DISABLED);

        assertThat(auth.login(EMAIL, PASSWORD, null)).isInstanceOf(SessionResult.Rejected.class);
    }

    // --- refresh

    @Test
    void refreshRotatesTheTokenAndRenewsItsLifetime() {
        SessionResult.Opened first = loginOk();
        clock.advance(Duration.ofMinutes(1));

        SessionResult.Opened second = (SessionResult.Opened) auth.refresh(first.tokens().refreshToken(), null);

        assertThat(second.tokens().refreshToken()).isNotEqualTo(first.tokens().refreshToken());
        assertThat(second.tokens().refreshExpiresAt()).isAfter(first.tokens().refreshExpiresAt());
        assertThat(audit.count("AUTH_REFRESH_SUCCESS")).isEqualTo(1);
    }

    @Test
    void reusingAnAlreadyRotatedTokenRevokesTheWholeSession() {
        SessionResult.Opened first = loginOk();
        SessionResult.Opened second = (SessionResult.Opened) auth.refresh(first.tokens().refreshToken(), null);

        assertThat(auth.refresh(first.tokens().refreshToken(), null)).isInstanceOf(SessionResult.Rejected.class);
        assertThat(audit.count("AUTH_REFRESH_REUSE_DETECTED")).isEqualTo(1);
        assertThat(auth.refresh(second.tokens().refreshToken(), null))
                .as("el token nuevo también quedó revocado").isInstanceOf(SessionResult.Rejected.class);
    }

    @Test
    void unknownOrBlankRefreshTokensAreRejected() {
        assertThat(auth.refresh("token-inventado", null)).isInstanceOf(SessionResult.Rejected.class);
        assertThat(auth.refresh(null, null)).isInstanceOf(SessionResult.Rejected.class);
        assertThat(auth.refresh("   ", null)).isInstanceOf(SessionResult.Rejected.class);
    }

    @Test
    void sessionHasAnAbsoluteCapThatRotationCannotExtend() {
        Instant start = clock.instant();
        String current = loginOk().tokens().refreshToken();

        for (int day = 6; day <= 24; day += 6) {
            clock.set(start.plus(Duration.ofDays(day)));
            SessionResult result = auth.refresh(current, null);
            assertThat(result).as("día %d", day).isInstanceOf(SessionResult.Opened.class);
            current = ((SessionResult.Opened) result).tokens().refreshToken();
        }
        clock.set(start.plus(Duration.ofDays(29)));
        SessionResult.Opened near = (SessionResult.Opened) auth.refresh(current, null);
        assertThat(near.tokens().refreshExpiresAt()).isBeforeOrEqualTo(start.plus(Duration.ofDays(30)));

        clock.set(start.plus(Duration.ofDays(31)));
        assertThat(auth.refresh(near.tokens().refreshToken(), null)).isInstanceOf(SessionResult.Rejected.class);
    }

    @Test
    void anAccountDisabledAfterLoginCannotRefresh() {
        SessionResult.Opened opened = loginOk();
        users.setStatus(userId, AccountStatus.DISABLED);

        assertThat(auth.refresh(opened.tokens().refreshToken(), null)).isInstanceOf(SessionResult.Rejected.class);
    }

    // --- logout

    @Test
    void logoutRevokesTheSessionAndIsIdempotent() {
        SessionResult.Opened opened = loginOk();
        String refresh = opened.tokens().refreshToken();

        auth.logout(refresh, null);
        auth.logout(refresh, null);
        auth.logout("desconocido", null);
        auth.logout(null, null);

        assertThat(auth.refresh(refresh, null)).isInstanceOf(SessionResult.Rejected.class);
        assertThat(audit.count("AUTH_LOGOUT")).isEqualTo(1);
    }

    // --- privacidad

    @Test
    void auditTrailNeverContainsPasswordsOrTokens() {
        SessionResult.Opened opened = loginOk();
        auth.login(EMAIL, "otra-clave-1234", null);
        auth.refresh(opened.tokens().refreshToken(), null);

        String dump = audit.records.toString();
        assertThat(dump).doesNotContain(PASSWORD).doesNotContain("otra-clave-1234")
                .doesNotContain(opened.tokens().refreshToken());
    }

    @Test
    void tokenPairAndCommandsHideTheirSecretsInToString() {
        SessionResult.Opened opened = loginOk();

        assertThat(opened.tokens().toString()).doesNotContain(opened.tokens().refreshToken())
                .doesNotContain(opened.tokens().accessToken());
        assertThat(new CreateUserCommand("a@b.co", "N", "secreta-123456", List.of("COACH")).toString())
                .doesNotContain("secreta-123456");
    }
}
