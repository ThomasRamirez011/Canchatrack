package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryRateLimits;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.MutableClock;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final InMemoryRateLimits store = new InMemoryRateLimits();

    private static RateLimitSettings settings(boolean enabled, int max) {
        RateLimitRule rule = new RateLimitRule(max, Duration.ofMinutes(10));
        Map<RateLimitBucket, RateLimitRule> rules = new java.util.EnumMap<>(RateLimitBucket.class);
        for (RateLimitBucket bucket : RateLimitBucket.values()) {
            rules.put(bucket, rule);
        }
        return new RateLimitSettings(enabled, rules);
    }

    private RateLimiter limiter(boolean enabled, int max) {
        return new RateLimiter(store, settings(enabled, max), clock);
    }

    private static ClientOrigin from(String ip) throws Exception {
        return new ClientOrigin(InetAddress.getByName(ip), "test");
    }

    @Test
    void attemptsUpToTheLimitPassAndTheNextOneIsRejectedWithTheWait() throws Exception {
        RateLimiter limiter = limiter(true, 3);
        ClientOrigin origin = from("203.0.113.7");

        for (int i = 0; i < 3; i++) {
            limiter.checkIp(RateLimitBucket.LOGIN_IP, origin);
            clock.advance(Duration.ofSeconds(10));
        }

        assertThatThrownBy(() -> limiter.checkIp(RateLimitBucket.LOGIN_IP, origin))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    // el primer intento fue hace 30 s: faltan 570 s para que salga de la ventana de 10 min
                    assertThat(e.getExtensions()).containsEntry("retryAfterSeconds", 571L);
                    assertThat(e.getMessage()).contains("571 segundos");
                });
    }

    @Test
    void aRejectedAttemptIsNotCountedSoInsistingDoesNotExtendTheBlock() throws Exception {
        RateLimiter limiter = limiter(true, 1);
        ClientOrigin origin = from("203.0.113.7");
        limiter.checkIp(RateLimitBucket.LOGIN_IP, origin);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.checkIp(RateLimitBucket.LOGIN_IP, origin)).isInstanceOf(DomainException.class);
        }

        assertThat(store.size()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        limiter.checkIp(RateLimitBucket.LOGIN_IP, origin); // la ventana se deslizó: vuelve a caber
    }

    @Test
    void eachBucketAndEachSubjectHasItsOwnCounter() throws Exception {
        RateLimiter limiter = limiter(true, 1);

        limiter.checkIp(RateLimitBucket.LOGIN_IP, from("203.0.113.7"));
        limiter.checkIp(RateLimitBucket.LOGIN_IP, from("203.0.113.8"));
        limiter.checkIp(RateLimitBucket.REGISTER_IP, from("203.0.113.7"));
        limiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, "ana@club.test");

        assertThatThrownBy(() -> limiter.checkIp(RateLimitBucket.LOGIN_IP, from("203.0.113.7")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void emailsAreCountedNormalizedAndEvenIfNoAccountExists() {
        RateLimiter limiter = limiter(true, 2);

        limiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, "Ana@Club.test");
        limiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, "  ana@club.TEST ");

        assertThatThrownBy(() -> limiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, "ANA@club.test"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void whenDisabledNothingIsCountedOrRejected() throws Exception {
        RateLimiter limiter = limiter(false, 1);

        for (int i = 0; i < 10; i++) {
            limiter.checkIp(RateLimitBucket.LOGIN_IP, from("203.0.113.7"));
            limiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, "ana@club.test");
        }

        assertThat(store.size()).isZero();
    }

    @Test
    void anUnknownOriginIsNotLimitedBecauseThereIsNoOneToBlame() {
        RateLimiter limiter = limiter(true, 1);

        limiter.checkIp(RateLimitBucket.LOGIN_IP, null);
        limiter.checkIp(RateLimitBucket.LOGIN_IP, ClientOrigin.UNKNOWN);
        limiter.checkIp(RateLimitBucket.LOGIN_IP, ClientOrigin.UNKNOWN);

        assertThat(store.size()).isZero();
    }

    @Test
    void aBlankEmailIsNotCounted() {
        RateLimiter limiter = limiter(true, 1);

        limiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, "   ");
        limiter.checkEmail(RateLimitBucket.RECOVERY_EMAIL, null);

        assertThat(store.size()).isZero();
    }

    @Test
    void theSubjectIsOnlyKeptAsAHash() throws Exception {
        assertThat(RateLimitSubjects.hash(RateLimitSubjects.ipKey(InetAddress.getByName("203.0.113.7"))))
                .matches("[0-9a-f]{64}").doesNotContain(".");
        assertThat(RateLimitSubjects.hash(RateLimitSubjects.emailKey("Ana@Club.test"))).matches("[0-9a-f]{64}");
    }

    @Test
    void ipv6AddressesAreGroupedByTheirSixtyFourBitPrefix() throws Exception {
        String a = RateLimitSubjects.ipKey(InetAddress.getByName("2001:db8:aaaa:bbbb:1:2:3:4"));
        String b = RateLimitSubjects.ipKey(InetAddress.getByName("2001:db8:aaaa:bbbb:ffff:eeee:dddd:cccc"));
        String other = RateLimitSubjects.ipKey(InetAddress.getByName("2001:db8:aaaa:cccc:1:2:3:4"));

        assertThat(a).isEqualTo(b).isNotEqualTo(other);
        assertThat(RateLimitSubjects.ipKey(InetAddress.getByName("203.0.113.7")))
                .isNotEqualTo(RateLimitSubjects.ipKey(InetAddress.getByName("203.0.113.8")));
    }

    @Test
    void theSettingsRequireARuleForEveryBucket() {
        Map<RateLimitBucket, RateLimitRule> incomplete = Map.of(RateLimitBucket.LOGIN_IP,
                new RateLimitRule(1, Duration.ofMinutes(5)));

        assertThatThrownBy(() -> new RateLimitSettings(true, incomplete)).isInstanceOf(IllegalArgumentException.class);
    }
}
