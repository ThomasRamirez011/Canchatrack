package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;

class MfaSettingsTest {

    private static MfaSettings with(String issuer, Duration ttl, int window, int codes) {
        return new MfaSettings(true, Set.of(RoleCode.COORDINATOR), issuer, ttl, window, codes);
    }

    @Test
    void acceptsReasonableValues() {
        MfaSettings settings = with("CanchaTrack", Duration.ofMinutes(5), 1, 10);

        assertThat(settings.requiredRoles().contains(RoleCode.COORDINATOR)).isTrue();
    }

    @Test
    void rejectsValuesThatWouldWeakenOrBreakIt() {
        assertThatThrownBy(() -> with(" ", Duration.ofMinutes(5), 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("X", Duration.ZERO, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("X", Duration.ofMinutes(-1), 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("X", Duration.ofMinutes(5), -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("X", Duration.ofMinutes(5), 4, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("X", Duration.ofMinutes(5), 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("X", Duration.ofMinutes(5), 1, 21)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDisabledSettingsRequireNobody() {
        MfaSettings disabled = MfaSettings.disabled();

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.requiredRoles().isEmpty()).isTrue();
    }
}
