package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryAccountTokens;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryMfaStore;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryRateLimits;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryRefreshTokens;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.MutableClock;
import co.edu.unipamplona.canchatrack.identity.domain.AccountTokenPurpose;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;

class SecurityMaintenanceServiceTest {

    private final MutableClock clock = new MutableClock();
    private final InMemoryRefreshTokens refresh = new InMemoryRefreshTokens();
    private final InMemoryAccountTokens account = new InMemoryAccountTokens();
    private final InMemoryRateLimits limits = new InMemoryRateLimits();
    private final InMemoryMfaStore mfa = new InMemoryMfaStore();
    private final SecurityMaintenanceService service = new SecurityMaintenanceService(refresh, account, limits, mfa,
            new AccountSecuritySettings(Duration.ofMinutes(30), Duration.ofHours(48), "", "/r", "/v", Duration.ofDays(7)), clock);

    private void refreshToken(String hash, Instant familyExpires, boolean revoke) {
        UUID family = UUID.randomUUID();
        Instant now = clock.instant();
        refresh.insert(new RefreshTokenStore.NewRefreshToken(UUID.randomUUID(), UUID.randomUUID(), family, hash, now.minusSeconds(60),
                now.plusSeconds(60).isBefore(familyExpires) ? now.plusSeconds(60) : familyExpires, familyExpires, ClientOrigin.UNKNOWN));
        if (revoke) {
            refresh.revokeFamily(family, now.minus(Duration.ofDays(8)), "LOGOUT");
        }
    }

    @Test
    void purgesOnlyWhatIsUselessAndOlderThanTheRetention() {
        Instant now = clock.instant();
        refreshToken("live".repeat(16), now.plus(Duration.ofDays(20)), false);          // sesión viva
        refreshToken("lost".repeat(16), now.minus(Duration.ofDays(8)), false);          // vencida hace 8 días
        refreshToken("recent".repeat(10) + "abcd", now.minus(Duration.ofDays(1)), false); // vencida ayer: aún se conserva
        refreshToken("gone".repeat(16), now.plus(Duration.ofDays(20)), true);           // revocada hace 8 días

        UUID user = UUID.randomUUID();
        account.insert(new AccountTokenStore.NewAccountToken(UUID.randomUUID(), user, AccountTokenPurpose.PASSWORD_RESET,
                "a".repeat(64), now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(9)).plusSeconds(60), null)); // vencido hace 9 días
        account.insert(new AccountTokenStore.NewAccountToken(UUID.randomUUID(), user, AccountTokenPurpose.PASSWORD_RESET,
                "b".repeat(64), now, now.plusSeconds(1800), null));                                                    // vigente

        limits.tryRecord(RateLimitBucket.LOGIN_IP, "x".repeat(64), new RateLimitRule(5, Duration.ofMinutes(15)),
                now.minus(Duration.ofHours(25)));
        limits.tryRecord(RateLimitBucket.LOGIN_IP, "y".repeat(64), new RateLimitRule(5, Duration.ofMinutes(15)), now);

        mfa.insertChallenge(new MfaStore.NewChallenge(UUID.randomUUID(), user, "c".repeat(64),
                co.edu.unipamplona.canchatrack.identity.domain.MfaStep.VERIFY, now.minus(Duration.ofDays(9)),
                now.minus(Duration.ofDays(9)).plusSeconds(300), null));                                                   // vencido hace 9 días
        mfa.insertChallenge(new MfaStore.NewChallenge(UUID.randomUUID(), user, "d".repeat(64),
                co.edu.unipamplona.canchatrack.identity.domain.MfaStep.VERIFY, now, now.plusSeconds(300), null));        // vigente

        SecurityMaintenanceService.Result result = service.purge();

        assertThat(result.refreshTokens()).isEqualTo(2);
        assertThat(result.accountTokens()).isEqualTo(1);
        assertThat(result.rateLimitEvents()).isEqualTo(1);
        assertThat(result.mfaChallenges()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(5);
        assertThat(mfa.challengeCount()).isEqualTo(1);
        assertThat(refresh.size()).isEqualTo(2);
        assertThat(account.size()).isEqualTo(1);
        assertThat(limits.size()).isEqualTo(1);
    }

    @Test
    void runningItTwiceDoesNotDeleteAnythingNew() {
        refreshToken("old1".repeat(16), clock.instant().minus(Duration.ofDays(30)), false);

        assertThat(service.purge().total()).isEqualTo(1);
        assertThat(service.purge().total()).isZero();
    }
}
