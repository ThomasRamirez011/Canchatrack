package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;


class PlayerAccessServiceTest {

    private final UUID guardianUser = UUID.randomUUID();
    private final UUID myPlayer = UUID.randomUUID();
    private final UUID otherPlayer = UUID.randomUUID();

    private final PlayerAccessService access = new PlayerAccessService(
            (userId, playerId) -> userId.equals(guardianUser) && playerId.equals(myPlayer));

    @Test
    void coordinatorAndCommitteeSeeEveryPlayer() {
        assertThat(access.canAccess(UUID.randomUUID(), Set.of("COORDINATOR"), otherPlayer)).isTrue();
        assertThat(access.canAccess(UUID.randomUUID(), Set.of("COMMITTEE"), otherPlayer)).isTrue();
    }

    @Test
    void guardianSeesOnlyLinkedPlayers() {
        assertThat(access.canAccess(guardianUser, Set.of("GUARDIAN"), myPlayer)).isTrue();
        assertThat(access.canAccess(guardianUser, Set.of("GUARDIAN"), otherPlayer)).isFalse();
    }

    @Test
    void coachAndEvaluatorAreDeniedUntilTheirScopeIsDefined() {
        assertThat(access.canAccess(UUID.randomUUID(), Set.of("COACH"), myPlayer)).isFalse();
        assertThat(access.canAccess(UUID.randomUUID(), Set.of("EVALUATOR"), myPlayer)).isFalse();
    }

    @Test
    void scopeFollowsTheSameRolesAsTheAccessRules() {
        assertThat(access.scopeOf(Set.of("COORDINATOR"))).isEqualTo(PlayerScope.ALL);
        assertThat(access.scopeOf(Set.of("COMMITTEE"))).isEqualTo(PlayerScope.ALL);
        assertThat(access.scopeOf(Set.of("GUARDIAN"))).isEqualTo(PlayerScope.LINKED_GUARDIAN);
        assertThat(access.scopeOf(Set.of("COACH", "EVALUATOR"))).isEqualTo(PlayerScope.NONE);
        assertThat(access.scopeOf(Set.of())).isEqualTo(PlayerScope.NONE);
        assertThat(access.scopeOf(null)).isEqualTo(PlayerScope.NONE);
        // quien tiene un rol amplio y el de acudiente conserva el alcance amplio
        assertThat(access.scopeOf(Set.of("GUARDIAN", "COORDINATOR"))).isEqualTo(PlayerScope.ALL);
    }

    @Test
    void missingInputsAreDenied() {
        assertThat(access.canAccess(null, Set.of("COORDINATOR"), myPlayer)).isFalse();
        assertThat(access.canAccess(guardianUser, null, myPlayer)).isFalse();
        assertThat(access.canAccess(guardianUser, Set.of("COORDINATOR"), null)).isFalse();
        assertThat(access.canAccess(guardianUser, Set.of(), myPlayer)).isFalse();
    }
}
