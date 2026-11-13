package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class AccountSecuritySettingsTest {

    private static AccountSecuritySettings settings(String appUrl, String resetPath) {
        return new AccountSecuritySettings(Duration.ofMinutes(30), Duration.ofHours(48), appUrl, resetPath, "/verify-email",
                Duration.ofDays(7));
    }

    @Test
    void linksJoinTheFrontAddressWithTheConfiguredPath() {
        AccountSecuritySettings settings = settings(" https://club.example.co/// ", "/reset-password");

        assertThat(settings.appUrl()).isEqualTo("https://club.example.co");
        assertThat(settings.resetUrl("abc")).isEqualTo("https://club.example.co/reset-password?token=abc");
        assertThat(settings.verifyUrl("xyz")).isEqualTo("https://club.example.co/verify-email?token=xyz");
    }

    @Test
    void withoutAFrontAddressThereIsNoLink() {
        assertThat(settings("", "/reset-password").resetUrl("abc")).isEmpty();
        assertThat(settings(null, "/reset-password").verifyUrl("abc")).isEmpty();
    }

    @Test
    void invalidPathsAndDurationsStopTheStartup() {
        assertThatThrownBy(() -> settings("https://a.co", "reset")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings("https://a.co", "/a?b=c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountSecuritySettings(Duration.ZERO, Duration.ofHours(1), "", "/r", "/v",
                Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountSecuritySettings(Duration.ofMinutes(5), Duration.ofHours(1), "", "/r", "/v",
                Duration.ofDays(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
