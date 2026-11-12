package co.edu.unipamplona.canchatrack.players.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.identity.api.PlayerData;
import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;
import co.edu.unipamplona.canchatrack.players.application.Fakes.InMemoryEnrollments;
import co.edu.unipamplona.canchatrack.players.application.Fakes.InMemoryPlayers;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class PlayerQueryServiceTest {

    private final InMemoryPlayers players = new InMemoryPlayers();
    private final InMemoryEnrollments enrollments = new InMemoryEnrollments();
    private final UUID allowedPlayer = UUID.randomUUID();
    private final UUID someUser = UUID.randomUUID();

    private final PlayerAccessPolicy access = new PlayerAccessPolicy() {
        @Override
        public boolean canAccess(UUID userId, Set<String> roleCodes, UUID playerId) {
            return playerId.equals(allowedPlayer);
        }

        @Override
        public PlayerScope scopeOf(Set<String> roleCodes) {
            return roleCodes.contains("COORDINATOR") ? PlayerScope.ALL
                    : roleCodes.contains("GUARDIAN") ? PlayerScope.LINKED_GUARDIAN : PlayerScope.NONE;
        }
    };

    private final PlayerQueryService service = new PlayerQueryService(players, enrollments, access);

    private static DomainException thrown(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e;
        }
        throw new AssertionError("se esperaba DomainException");
    }

    @Test
    void noAccessAndNoExistenceLookTheSame() {
        DomainException denied = thrown(() -> service.get(someUser, Set.of("GUARDIAN"), UUID.randomUUID()));
        DomainException missing = thrown(() -> service.get(someUser, Set.of("GUARDIAN"), allowedPlayer)); // permitido, pero no existe

        assertThat(denied.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(missing.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(denied.getMessage()).isEqualTo(missing.getMessage());
    }

    @Test
    void returnsThePlayerWithItsEnrollmentsWhenAccessIsGranted() {
        var seeded = players.seed("TI-1", PlayerStatus.ACTIVE, null);
        UUID id = seeded.id();
        PlayerQueryService open = new PlayerQueryService(players, enrollments, new PlayerAccessPolicy() {
            @Override
            public boolean canAccess(UUID userId, Set<String> roleCodes, UUID playerId) {
                return true;
            }

            @Override
            public PlayerScope scopeOf(Set<String> roleCodes) {
                return PlayerScope.ALL;
            }
        });
        enrollments.insert(id, UUID.randomUUID(), UUID.randomUUID(), null, someUser);

        PlayerWithEnrollments view = open.get(someUser, Set.of("COORDINATOR"), id);

        assertThat(view.player().code()).isEqualTo(seeded.code());
        assertThat(view.enrollments()).hasSize(1);
    }

    @Test
    void staffWithOnlyBasicAccessGetsNoIdentityDocumentAndNoEnrollments() {
        var seeded = players.seed("TI-9", PlayerStatus.ACTIVE, null);
        UUID id = seeded.id();
        enrollments.insert(id, UUID.randomUUID(), UUID.randomUUID(), null, someUser);
        PlayerQueryService staff = new PlayerQueryService(players, enrollments, new PlayerAccessPolicy() {
            @Override
            public boolean canAccess(UUID userId, Set<String> roleCodes, UUID playerId) {
                return false;
            }

            @Override
            public boolean canView(UUID userId, Set<String> roleCodes, UUID playerId, PlayerData data) {
                return data == PlayerData.BASIC && playerId.equals(id);
            }

            @Override
            public PlayerScope scopeOf(Set<String> roleCodes) {
                return PlayerScope.ASSIGNED_STAFF;
            }
        });

        PlayerWithEnrollments view = staff.get(someUser, Set.of("COACH"), id);

        assertThat(view.player().code()).isEqualTo(seeded.code());
        assertThat(view.player().documentType()).isNull();
        assertThat(view.player().documentNumber()).isNull();
        assertThat(view.enrollments()).isEmpty();
        assertThat(thrown(() -> staff.get(someUser, Set.of("COACH"), UUID.randomUUID())).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void assignedStaffMayList() {
        PlayerQueryService staff = new PlayerQueryService(players, enrollments, new PlayerAccessPolicy() {
            @Override
            public boolean canAccess(UUID userId, Set<String> roleCodes, UUID playerId) {
                return false;
            }

            @Override
            public PlayerScope scopeOf(Set<String> roleCodes) {
                return PlayerScope.ASSIGNED_STAFF;
            }
        });

        assertThat(staff.list(someUser, Set.of("COACH"), null, 0, 20).items()).isEmpty();
    }

    @Test
    void rolesWithoutScopeCannotList() {
        assertThat(thrown(() -> service.list(someUser, Set.of("COACH"), null, 0, 20)).getCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    void anUnknownStatusFilterIsAValidationError() {
        DomainException e = thrown(() -> service.list(someUser, Set.of("COORDINATOR"), "NOPE", 0, 20));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void pageAndSizeAreClamped() {
        players.seed("TI-1", PlayerStatus.ACTIVE, null);

        PageResult<PlayerSummary> tooBig = service.list(someUser, Set.of("COORDINATOR"), null, -5, 10_000);
        PageResult<PlayerSummary> tooSmall = service.list(someUser, Set.of("COORDINATOR"), "ACTIVE", 2, 0);

        assertThat(tooBig.page()).isZero();
        assertThat(tooBig.size()).isEqualTo(100);
        assertThat(tooSmall.size()).isEqualTo(1);
        assertThat(tooSmall.items()).extracting(PlayerSummary::status).containsOnly(PlayerStatus.ACTIVE);
        assertThat(List.of(tooBig, tooSmall)).hasSize(2);
    }
}
