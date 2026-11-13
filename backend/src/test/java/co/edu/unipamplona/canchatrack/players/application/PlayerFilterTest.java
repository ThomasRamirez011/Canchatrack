package co.edu.unipamplona.canchatrack.players.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

class PlayerFilterTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void noScopeMatchesNothing() {
        assertThat(new PlayerFilter(PlayerScope.NONE, USER, null).matchesNothing()).isTrue();
    }

    @Test
    void scopesThatNeedAnAccountMatchNothingWithoutOne() {
        assertThat(new PlayerFilter(PlayerScope.LINKED_GUARDIAN, null, null).matchesNothing()).isTrue();
        assertThat(new PlayerFilter(PlayerScope.ASSIGNED_STAFF, null, null).matchesNothing()).isTrue();
    }

    @Test
    void scopesThatNeedAnAccountMatchWithOne() {
        assertThat(new PlayerFilter(PlayerScope.LINKED_GUARDIAN, USER, null).matchesNothing()).isFalse();
        assertThat(new PlayerFilter(PlayerScope.ASSIGNED_STAFF, USER, PlayerStatus.SUBMITTED).matchesNothing()).isFalse();
    }

    @Test
    void everythingScopeNeedsNoAccount() {
        for (PlayerScope scope : PlayerScope.values()) {
            if (scope != PlayerScope.NONE && scope != PlayerScope.LINKED_GUARDIAN && scope != PlayerScope.ASSIGNED_STAFF) {
                assertThat(new PlayerFilter(scope, null, null).matchesNothing()).as(scope.name()).isFalse();
            }
        }
    }
}
