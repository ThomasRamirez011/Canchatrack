package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.api.PlayerData;
import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;


class PlayerAccessServiceTest {

    private final UUID guardianUser = UUID.randomUUID();
    private final UUID myPlayer = UUID.randomUUID();
    private final UUID otherPlayer = UUID.randomUUID();

    private final UUID coachUser = UUID.randomUUID();
    private final UUID evaluatorUser = UUID.randomUUID();
    private final UUID coachedPlayer = UUID.randomUUID();
    private final UUID evaluatedPlayer = UUID.randomUUID();

    private final StaffAssignments assignments = new StaffAssignments() {
        @Override
        public boolean isHeadCoachOf(UUID userId, UUID playerId) {
            return userId.equals(coachUser) && playerId.equals(coachedPlayer);
        }

        @Override
        public boolean isEvaluatorOf(UUID userId, UUID playerId) {
            return (userId.equals(evaluatorUser) || userId.equals(coachUser)) && playerId.equals(evaluatedPlayer);
        }
    };

    private final PlayerAccessService access = new PlayerAccessService(
            (userId, playerId) -> userId.equals(guardianUser) && playerId.equals(myPlayer), assignments);

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
    void coachAndEvaluatorNeverGetFullAccess() {
        // el acceso completo (pagos, documentos, contacto) sigue cerrado para el personal deportivo
        assertThat(access.canAccess(coachUser, Set.of("COACH"), coachedPlayer)).isFalse();
        assertThat(access.canAccess(evaluatorUser, Set.of("EVALUATOR"), evaluatedPlayer)).isFalse();
    }

    @Test
    void headCoachSeesBasicAttendanceAndMatchesOfTheirOwnPlayersOnly() {
        for (PlayerData data : PlayerData.values()) {
            assertThat(access.canView(coachUser, Set.of("COACH"), coachedPlayer, data)).isTrue();
            assertThat(access.canView(coachUser, Set.of("COACH"), otherPlayer, data)).isFalse();
        }
    }

    @Test
    void anotherCoachDoesNotSeeThePlayer() {
        assertThat(access.canView(UUID.randomUUID(), Set.of("COACH"), coachedPlayer, PlayerData.BASIC)).isFalse();
    }

    @Test
    void theAssignmentIsNotEnoughWithoutTheRole() {
        assertThat(access.canView(coachUser, Set.of("GUARDIAN"), coachedPlayer, PlayerData.BASIC)).isFalse();
        assertThat(access.canView(coachUser, Set.of(), coachedPlayer, PlayerData.BASIC)).isFalse();
    }

    @Test
    void assignedEvaluatorSeesOnlyTheBasicData() {
        assertThat(access.canView(evaluatorUser, Set.of("EVALUATOR"), evaluatedPlayer, PlayerData.BASIC)).isTrue();
        assertThat(access.canView(evaluatorUser, Set.of("EVALUATOR"), evaluatedPlayer, PlayerData.ATTENDANCE)).isFalse();
        assertThat(access.canView(evaluatorUser, Set.of("EVALUATOR"), evaluatedPlayer, PlayerData.MATCHES)).isFalse();
        assertThat(access.canView(evaluatorUser, Set.of("EVALUATOR"), otherPlayer, PlayerData.BASIC)).isFalse();
    }

    @Test
    void aCoachWhoSitsOnTheEvaluationPanelSeesTheBasicDataOfThatPlayer() {
        assertThat(access.canView(coachUser, Set.of("COACH"), evaluatedPlayer, PlayerData.BASIC)).isTrue();
        assertThat(access.canView(coachUser, Set.of("COACH"), evaluatedPlayer, PlayerData.ATTENDANCE)).isFalse();
    }

    @Test
    void fullAccessAlwaysImpliesEveryKindOfData() {
        for (PlayerData data : PlayerData.values()) {
            assertThat(access.canView(UUID.randomUUID(), Set.of("COORDINATOR"), otherPlayer, data)).isTrue();
            assertThat(access.canView(guardianUser, Set.of("GUARDIAN"), myPlayer, data)).isTrue();
            assertThat(access.canView(guardianUser, Set.of("GUARDIAN"), otherPlayer, data)).isFalse();
        }
    }

    @Test
    void canViewDeniesMissingInputs() {
        assertThat(access.canView(null, Set.of("COACH"), coachedPlayer, PlayerData.BASIC)).isFalse();
        assertThat(access.canView(coachUser, null, coachedPlayer, PlayerData.BASIC)).isFalse();
        assertThat(access.canView(coachUser, Set.of("COACH"), null, PlayerData.BASIC)).isFalse();
        assertThat(access.canView(coachUser, Set.of("COACH"), coachedPlayer, null)).isFalse();
    }

    @Test
    void scopeFollowsTheSameRolesAsTheAccessRules() {
        assertThat(access.scopeOf(Set.of("COORDINATOR"))).isEqualTo(PlayerScope.ALL);
        assertThat(access.scopeOf(Set.of("COMMITTEE"))).isEqualTo(PlayerScope.ALL);
        assertThat(access.scopeOf(Set.of("GUARDIAN"))).isEqualTo(PlayerScope.LINKED_GUARDIAN);
        assertThat(access.scopeOf(Set.of("COACH"))).isEqualTo(PlayerScope.ASSIGNED_STAFF);
        assertThat(access.scopeOf(Set.of("EVALUATOR"))).isEqualTo(PlayerScope.ASSIGNED_STAFF);
        assertThat(access.scopeOf(Set.of("COACH", "EVALUATOR"))).isEqualTo(PlayerScope.ASSIGNED_STAFF);
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
