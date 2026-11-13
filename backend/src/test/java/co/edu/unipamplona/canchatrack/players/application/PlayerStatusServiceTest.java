package co.edu.unipamplona.canchatrack.players.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.players.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class PlayerStatusServiceTest {

    private final UUID playerId = UUID.randomUUID();
    private final UUID coordinatorId = UUID.randomUUID();
    private final AdminActor actor = new AdminActor(coordinatorId, null, "test");
    private final RecordingAudit audit = new RecordingAudit();
    private FakeStore store;
    private PlayerStatusService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore(PlayerStatus.ACTIVE);
        service = new PlayerStatusService(store, audit);
    }

    @Test
    void suspendsAnActivePlayerRecordingHistoryAndAudit() {
        PlayerStatusChange change = service.suspend(actor, playerId, "  Lesión prolongada  ");

        assertThat(change.previousStatus()).isEqualTo(PlayerStatus.ACTIVE);
        assertThat(change.status()).isEqualTo(PlayerStatus.SUSPENDED);
        assertThat(store.status).isEqualTo(PlayerStatus.SUSPENDED);
        assertThat(store.history).containsExactly("T-13:ACTIVE>SUSPENDED:Lesión prolongada:" + coordinatorId);
        AuditRecord record = audit.inTransaction.get(0);
        assertThat(record.action()).isEqualTo("PLAYER_SUSPENDED");
        assertThat(record.entityId()).isEqualTo(playerId);
        assertThat(record.reason()).isEqualTo("Lesión prolongada");
        assertThat(record.actorRole()).isEqualTo("COORDINATOR");
    }

    @Test
    void suspendingRequiresAReason() {
        for (String reason : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.suspend(actor, playerId, reason))
                    .isInstanceOf(DomainException.class)
                    .satisfies(e -> assertThat(((DomainException) e).getExtensions().get("errors").toString()).contains("field=reason"));
        }
        assertThat(store.status).isEqualTo(PlayerStatus.ACTIVE);
        assertThat(audit.inTransaction).isEmpty();
    }

    @Test
    void theReasonCannotBeTooLong() {
        assertThatThrownBy(() -> service.suspend(actor, playerId, "x".repeat(501))).isInstanceOf(DomainException.class);
        assertThat(store.status).isEqualTo(PlayerStatus.ACTIVE);
    }

    @Test
    void onlyAnActivePlayerCanBeSuspended() {
        for (PlayerStatus status : List.of(PlayerStatus.IN_EVALUATION, PlayerStatus.SUSPENDED, PlayerStatus.WITHDRAWN,
                PlayerStatus.SEASON_CLOSED, PlayerStatus.PENDING_REGISTRATION)) {
            store.status = status;
            assertThatThrownBy(() -> service.suspend(actor, playerId, "motivo"))
                    .isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT));
            assertThat(store.status).isEqualTo(status);
        }
        assertThat(audit.inTransaction).isEmpty();
    }

    @Test
    void reinstatesASuspendedPlayerWithoutAReason() {
        store.status = PlayerStatus.SUSPENDED;

        PlayerStatusChange change = service.reinstate(actor, playerId, null);

        assertThat(change.status()).isEqualTo(PlayerStatus.ACTIVE);
        assertThat(store.history).containsExactly("T-14:SUSPENDED>ACTIVE:null:" + coordinatorId);
        assertThat(audit.inTransaction.get(0).action()).isEqualTo("PLAYER_REINSTATED");
        assertThat(audit.inTransaction.get(0).reason()).isNull();
    }

    @Test
    void onlyASuspendedPlayerCanBeReinstated() {
        assertThatThrownBy(() -> service.reinstate(actor, playerId, "ya está activo"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT));
    }

    @Test
    void unknownPlayersAreNotFound() {
        store.exists = false;
        assertThatThrownBy(() -> service.suspend(actor, playerId, "motivo"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.reinstate(actor, playerId, null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void aLostRaceOnTheTransitionIsAConflict() {
        store.refuseTransition = true;
        assertThatThrownBy(() -> service.suspend(actor, playerId, "motivo"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT));
        assertThat(store.history).isEmpty();
        assertThat(audit.inTransaction).isEmpty();
    }

    private final class FakeStore implements PlacementStore {

        PlayerStatus status;
        boolean exists = true;
        boolean refuseTransition;
        final List<String> history = new ArrayList<>();

        FakeStore(PlayerStatus status) {
            this.status = status;
        }

        @Override
        public Optional<PlayerRecord> lockPlayer(UUID id) {
            return exists ? Optional.of(new PlayerRecord(id, "JG-000001", status)) : Optional.empty();
        }

        @Override
        public Optional<UUID> currentCategoryId(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<UUID> currentPanel(UUID id) {
            return List.of();
        }

        @Override
        public boolean transition(UUID id, PlayerStatus from, PlayerStatus to) {
            if (refuseTransition || status != from) {
                return false;
            }
            status = to;
            return true;
        }

        @Override
        public void closeCurrentCategoryAssignment(UUID id) {
        }

        @Override
        public void insertPromotionAssignment(UUID id, UUID categoryId, UUID headCoachId, UUID roundId, UUID createdBy) {
        }

        @Override
        public void recordStateChange(UUID id, String code, PlayerStatus from, PlayerStatus to, String reason, UUID actorId) {
            history.add(code + ":" + from + ">" + to + ":" + reason + ":" + actorId);
        }
    }
}
