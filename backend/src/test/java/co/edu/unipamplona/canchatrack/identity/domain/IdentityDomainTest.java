package co.edu.unipamplona.canchatrack.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdentityDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private static UserAccount account(int failedAttempts, Instant lockedUntil, AccountStatus status, String hash) {
        return new UserAccount(UUID.randomUUID(), "a@club.test", "A", hash, status, failedAttempts, lockedUntil,
                Set.of(RoleCode.COACH), NOW);
    }

    // --- LockoutPolicy

    @Test
    void failuresBelowTheLimitOnlyCount() {
        LockoutPolicy policy = new LockoutPolicy(5, Duration.ofMinutes(15));

        LockoutPolicy.State state = policy.afterFailure(account(3, null, AccountStatus.ACTIVE, "h"), NOW);

        assertThat(state.failedAttempts()).isEqualTo(4);
        assertThat(state.locked()).isFalse();
    }

    @Test
    void reachingTheLimitLocksTheAccountAndResetsTheCounter() {
        LockoutPolicy policy = new LockoutPolicy(5, Duration.ofMinutes(15));

        LockoutPolicy.State state = policy.afterFailure(account(4, null, AccountStatus.ACTIVE, "h"), NOW);

        assertThat(state.locked()).isTrue();
        assertThat(state.lockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(state.failedAttempts()).isZero();
    }

    @Test
    void lockoutPolicyRejectsNonsensicalSettings() {
        assertThatThrownBy(() -> new LockoutPolicy(0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockoutPolicy(3, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- UserAccount

    @Test
    void onlyActiveUnlockedAccountsWithPasswordCanAuthenticate() {
        assertThat(account(0, null, AccountStatus.ACTIVE, "h").canAuthenticate(NOW)).isTrue();
        assertThat(account(0, NOW.plusSeconds(60), AccountStatus.ACTIVE, "h").canAuthenticate(NOW)).isFalse();
        assertThat(account(0, NOW.minusSeconds(1), AccountStatus.ACTIVE, "h").canAuthenticate(NOW)).isTrue();
        assertThat(account(0, null, AccountStatus.DISABLED, "h").canAuthenticate(NOW)).isFalse();
        assertThat(account(0, null, AccountStatus.LOCKED, "h").canAuthenticate(NOW)).isFalse();
        assertThat(account(0, null, AccountStatus.ACTIVE, null).canAuthenticate(NOW)).isFalse(); // solo OIDC
    }

    // --- PasswordPolicy

    @Test
    void acceptableOrRejectedPasswords() {
        assertThat(PasswordPolicy.violations("Entrenador-2026", "x@club.test")).isEmpty();
        assertThat(PasswordPolicy.violations("cor1", "x@club.test")).isNotEmpty();
        assertThat(PasswordPolicy.violations("soloLetrasLargas", "x@club.test")).isNotEmpty();
        assertThat(PasswordPolicy.violations("123456789012345", "x@club.test")).isNotEmpty();
        assertThat(PasswordPolicy.violations(null, "x@club.test")).isNotEmpty();
        assertThat(PasswordPolicy.violations("x@club.test1", "x@club.test1")).isNotEmpty(); // igual al correo
        assertThat(PasswordPolicy.violations("a1".repeat(40), "x@club.test")).isNotEmpty(); // > 72 bytes
    }

    @Test
    void passwordMinimumIsSixCharacters() {
        assertThat(PasswordPolicy.violations("AdmiN01", "x@club.test")).isEmpty();
        assertThat(PasswordPolicy.violations("Abcd12", "x@club.test")).isEmpty();
        assertThat(PasswordPolicy.violations("Abcd1", "x@club.test")).isNotEmpty();
    }

    @Test
    void passwordViolationsNeverEchoThePassword() {
        String secret = "Secreta-Corta1";
        assertThat(String.join(" ", PasswordPolicy.violations(secret, "x@club.test"))).doesNotContain(secret);
        assertThat(String.join(" ", PasswordPolicy.violations("abc", "x@club.test"))).doesNotContain("abc");
    }

    // --- Emails y RoleCode

    @Test
    void emailsAreNormalizedAndValidated() {
        assertThat(Emails.normalize("  Coord@Club.TEST ")).isEqualTo("coord@club.test");
        assertThat(Emails.normalize(null)).isEmpty();
        assertThat(Emails.isValid("coord@club.test")).isTrue();
        assertThat(Emails.isValid("sin-arroba")).isFalse();
        assertThat(Emails.isValid("a b@club.test")).isFalse();
        assertThat(Emails.isValid("a@b")).isFalse();
        assertThat(Emails.isValid("a@" + "b".repeat(250) + ".co")).isFalse();
    }

    @Test
    void roleCodesParseOnlyKnownValues() {
        assertThat(RoleCode.parse("COACH")).contains(RoleCode.COACH);
        assertThat(RoleCode.parse(" COMMITTEE ")).contains(RoleCode.COMMITTEE);
        assertThat(RoleCode.parse("coach")).isEmpty();
        assertThat(RoleCode.parse("ROOT")).isEmpty();
        assertThat(RoleCode.parse(null)).isEmpty();
    }

    // --- RefreshToken

    @Test
    void refreshTokenStatusFollowsRevocationUseAndExpiry() {
        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        Instant issued = NOW.minusSeconds(60);
        Instant expires = NOW.plusSeconds(600);
        Instant familyExpires = NOW.plusSeconds(3600);

        assertThat(new RefreshToken(id, user, family, issued, expires, familyExpires, null, null).statusAt(NOW))
                .isEqualTo(RefreshToken.Status.ACTIVE);
        assertThat(new RefreshToken(id, user, family, issued, expires, familyExpires, null, null).statusAt(expires))
                .isEqualTo(RefreshToken.Status.EXPIRED);
        assertThat(new RefreshToken(id, user, family, issued, expires, NOW, null, null).statusAt(NOW))
                .as("el tope absoluto de la sesión también vence").isEqualTo(RefreshToken.Status.EXPIRED);
        assertThat(new RefreshToken(id, user, family, issued, expires, familyExpires, NOW.minusSeconds(5), null).statusAt(NOW))
                .isEqualTo(RefreshToken.Status.USED);
        assertThat(new RefreshToken(id, user, family, issued, expires, familyExpires, NOW.minusSeconds(5), NOW.minusSeconds(1)).statusAt(NOW))
                .as("revocado prevalece sobre usado").isEqualTo(RefreshToken.Status.REVOKED);
    }
}
