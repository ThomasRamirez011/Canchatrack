package co.edu.unipamplona.canchatrack.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class RateLimitRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

    @Test
    void theLimitIsReachedWhenTheCountEqualsTheMaximum() {
        RateLimitRule rule = new RateLimitRule(3, Duration.ofMinutes(15));

        assertThat(rule.isExceeded(0)).isFalse();
        assertThat(rule.isExceeded(2)).isFalse();
        assertThat(rule.isExceeded(3)).isTrue();
        assertThat(rule.isExceeded(40)).isTrue();
    }

    @Test
    void theWindowEndsAtNowAndSlidesBack() {
        RateLimitRule rule = new RateLimitRule(3, Duration.ofMinutes(15));

        assertThat(rule.windowStart(NOW)).isEqualTo(Instant.parse("2026-11-12T11:45:00Z"));
    }

    @Test
    void retryAfterWaitsForTheOldestEventToLeaveTheWindow() {
        RateLimitRule rule = new RateLimitRule(3, Duration.ofMinutes(15));
        List<Instant> inWindow = List.of(NOW.minusSeconds(60), NOW.minusSeconds(600), NOW.minusSeconds(300));

        // el más antiguo cumple 15 min dentro de 300 s; se suma 1 s para quedar fuera de la ventana
        assertThat(rule.retryAfterSeconds(inWindow, NOW)).isEqualTo(301);
    }

    @Test
    void retryAfterIsAtLeastOneSecondAndNeverMoreThanTheWindow() {
        RateLimitRule rule = new RateLimitRule(1, Duration.ofMinutes(1));

        assertThat(rule.retryAfterSeconds(List.of(NOW.minusSeconds(59)), NOW)).isEqualTo(2);
        assertThat(rule.retryAfterSeconds(List.of(NOW.minusSeconds(100)), NOW)).isEqualTo(1);
        assertThat(rule.retryAfterSeconds(List.of(NOW.plusSeconds(1000)), NOW)).isEqualTo(60);
        assertThat(rule.retryAfterSeconds(List.of(), NOW)).isBetween(1L, 60L);
    }

    @Test
    void invalidRulesAreRejectedAtStartup() {
        assertThatThrownBy(() -> new RateLimitRule(0, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitRule(RateLimitRule.MAX_ALLOWED + 1, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitRule(5, Duration.ofSeconds(59))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitRule(5, Duration.ofHours(25))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitRule(5, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAccountTokenIsUsableOnlyOnceAndBeforeItExpires() {
        java.util.UUID id = java.util.UUID.randomUUID();
        java.util.UUID user = java.util.UUID.randomUUID();
        Instant expires = NOW.plusSeconds(1800);

        assertThat(new AccountToken(id, user, AccountTokenPurpose.PASSWORD_RESET, expires, null, null).isUsableAt(NOW)).isTrue();
        assertThat(new AccountToken(id, user, AccountTokenPurpose.PASSWORD_RESET, expires, NOW, null).isUsableAt(NOW)).isFalse();
        assertThat(new AccountToken(id, user, AccountTokenPurpose.PASSWORD_RESET, expires, null, NOW).isUsableAt(NOW)).isFalse();
        assertThat(new AccountToken(id, user, AccountTokenPurpose.PASSWORD_RESET, expires, null, null).isUsableAt(expires)).isFalse();
    }
}
