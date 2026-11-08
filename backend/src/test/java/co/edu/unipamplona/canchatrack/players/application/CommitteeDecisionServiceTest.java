package co.edu.unipamplona.canchatrack.players.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.players.application.CommitteeFakes.CommitteeAwareCategories;
import co.edu.unipamplona.canchatrack.players.application.CommitteeFakes.FakeFiles;
import co.edu.unipamplona.canchatrack.players.application.CommitteeFakes.FakeStaff;
import co.edu.unipamplona.canchatrack.players.application.CommitteeFakes.InMemoryCommittee;
import co.edu.unipamplona.canchatrack.players.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class CommitteeDecisionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private final InMemoryCommittee committee = new InMemoryCommittee();
    private final CommitteeAwareCategories categories = new CommitteeAwareCategories(committee);
    private final FakeStaff staff = new FakeStaff();
    private final FakeFiles files = new FakeFiles();
    private final RecordingAudit audit = new RecordingAudit();
    private final CommitteeActor actor = new CommitteeActor(UUID.randomUUID(), null, "JUnit");

    private CommitteeDecisionService service;
    private UUID coach;
    private UUID evaluatorA;
    private UUID evaluatorB;
    private UUID evaluatorC;
    private UUID file;
    private CategoryInfo category;

    @BeforeEach
    void setUp() {
        service = serviceWith(true);
        coach = staff.add("COACH");
        evaluatorA = staff.add("EVALUATOR");
        evaluatorB = staff.add("EVALUATOR");
        evaluatorC = staff.add("COMMITTEE");
        file = files.add();
        category = categories.add("Sub-12", 2014, 2015, 2, 0, true);
    }

    private CommitteeDecisionService serviceWith(boolean allowCapacityException) {
        return new CommitteeDecisionService(committee, categories, staff, files,
                CommitteeSettings.of(List.of("EVALUATOR", "COACH", "COMMITTEE"), allowCapacityException, ZONE), audit);
    }

    private MinuteData minute(String number) {
        return new MinuteData(number, LocalDate.of(2026, 11, 5), List.of("  Ana Gómez ", "Luis Pérez"), file);
    }

    private AssignCategoryCommand assignment(String minuteNumber, UUID categoryId, String exceptionReason) {
        return new AssignCategoryCommand(minute(minuteNumber), categoryId, coach,
                List.of(evaluatorA, evaluatorB, evaluatorC), exceptionReason);
    }

    private AssignCategoryCommand assignment() {
        return assignment("ACTA-1", category.id(), null);
    }

    private static DomainException thrown(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e;
        }
        throw new AssertionError("se esperaba una DomainException");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorFields(DomainException e) {
        return ((List<Map<String, String>>) e.getExtensions().get("errors")).stream().map(m -> m.get("field")).toList();
    }

    private AuditRecord lastAudit() {
        return audit.inTransaction.get(audit.inTransaction.size() - 1);
    }

    // ---------- asignar ----------

    @Test
    void assigningMovesThePlayerToCategoryAssignedWithCoachPanelAndMinute() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);

        AssignmentView view = service.assign(actor, player, assignment());

        assertThat(committee.players.get(player)).isEqualTo(PlayerStatus.CATEGORY_ASSIGNED);
        assertThat(view.categoryId()).isEqualTo(category.id());
        assertThat(view.headCoachId()).isEqualTo(coach);
        assertThat(view.evaluatorIds()).hasSize(3);
        assertThat(view.minuteNumber()).isEqualTo("ACTA-1");
        assertThat(view.minuteFileId()).isEqualTo(file);
        assertThat(view.capacityExceptionReason()).isNull();
        assertThat(committee.minutes.get(0).decision).isEqualTo("ASSIGNED");
        assertThat(committee.minutes.get(0).data.attendees()).isEqualTo(List.of("Ana Gómez", "Luis Pérez"));
        assertThat(audit.inTransaction).hasSize(1);
        assertThat(lastAudit().action()).isEqualTo("PLAYER_CATEGORY_ASSIGNED");
        assertThat(lastAudit().actorRole()).isEqualTo("COMMITTEE");
        assertThat(lastAudit().entityId()).isEqualTo(player);
    }

    @Test
    void aPlayerPendingCommitteeCanAlsoBeDecided() {
        UUID player = committee.addPlayer(PlayerStatus.COMMITTEE_PENDING);
        service.assign(actor, player, assignment());
        assertThat(committee.players.get(player)).isEqualTo(PlayerStatus.CATEGORY_ASSIGNED);
    }

    @Test
    void anUnknownPlayerIsNotFound() {
        assertThat(thrown(() -> service.assign(actor, UUID.randomUUID(), assignment())).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void onlyAPendingPlayerCanBeDecidedAndRepeatingTheRequestChangesNothing() {
        for (PlayerStatus status : List.of(PlayerStatus.ACTIVE, PlayerStatus.REJECTED, PlayerStatus.WITHDRAWN,
                PlayerStatus.CATEGORY_ASSIGNED, PlayerStatus.SEASON_CLOSED)) {
            UUID player = committee.addPlayer(status);
            assertThat(thrown(() -> service.assign(actor, player, assignment())).getCode())
                    .isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT);
            assertThat(thrown(() -> service.reject(actor, player,
                    new RejectEnrollmentCommand(minute("ACTA-9"), "No cumple"))).getCode())
                    .isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT);
        }
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        service.assign(actor, player, assignment());
        assertThat(thrown(() -> service.assign(actor, player, assignment())).getCode()).isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT);
        assertThat(committee.minutes).hasSize(1);
        assertThat(committee.currentAssignments(player)).isEqualTo(1L);
        assertThat(audit.inTransaction).hasSize(1);
    }

    // ---------- validación acumulada ----------

    @Test
    void panelProblemsAreReportedAsPanelInvalidTogetherWithTheOtherFieldErrors() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        AssignCategoryCommand bad = new AssignCategoryCommand(
                new MinuteData(" ", null, List.of(), null), null, coach, List.of(evaluatorA, evaluatorB), null);

        DomainException e = thrown(() -> service.assign(actor, player, bad));

        assertThat(e.getCode()).isEqualTo(ErrorCode.PANEL_INVALID);
        assertThat(errorFields(e)).containsExactly("minuteNumber", "meetingDate", "attendees", "minuteFileId",
                "categoryId", "evaluatorIds");
        assertThat(committee.players.get(player)).isEqualTo(PlayerStatus.SUBMITTED);
    }

    @Test
    void withoutPanelProblemsTheCodeIsValidationFailed() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        DomainException e = thrown(() -> service.assign(actor, player, assignment(" ", category.id(), null)));
        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).containsExactly("minuteNumber");
    }

    @Test
    void theMeetingCannotBeInTheFutureButTodayIsFine() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        MinuteData tomorrow = new MinuteData("ACTA-1", committee.today.plusDays(1), List.of("Ana"), file);
        AssignCategoryCommand future = new AssignCategoryCommand(tomorrow, category.id(), coach,
                List.of(evaluatorA, evaluatorB, evaluatorC), null);
        assertThat(errorFields(thrown(() -> service.assign(actor, player, future)))).containsExactly("meetingDate");

        MinuteData today = new MinuteData("ACTA-1", committee.today, List.of("Ana"), file);
        service.assign(actor, player, new AssignCategoryCommand(today, category.id(), coach,
                List.of(evaluatorA, evaluatorB, evaluatorC), null));
        assertThat(committee.players.get(player)).isEqualTo(PlayerStatus.CATEGORY_ASSIGNED);
    }

    @Test
    void theMinuteFileMustExist() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        AssignCategoryCommand missingFile = new AssignCategoryCommand(
                new MinuteData("ACTA-1", LocalDate.of(2026, 11, 5), List.of("Ana"), UUID.randomUUID()),
                category.id(), coach, List.of(evaluatorA, evaluatorB, evaluatorC), null);
        assertThat(errorFields(thrown(() -> service.assign(actor, player, missingFile)))).containsExactly("minuteFileId");
    }

    @Test
    void theHeadCoachMustHoldTheCoachRole() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        UUID notACoach = staff.add("EVALUATOR");
        AssignCategoryCommand command = new AssignCategoryCommand(minute("ACTA-1"), category.id(), notACoach,
                List.of(evaluatorA, evaluatorB, evaluatorC), null);
        DomainException e = thrown(() -> service.assign(actor, player, command));
        assertThat(e.getCode()).isEqualTo(ErrorCode.PANEL_INVALID);
        assertThat(errorFields(e)).containsExactly("headCoachId");
    }

    @Test
    void panelMembersNeedAnActiveAccountWithAnAdmittedRole() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        UUID guardianOnly = staff.add("GUARDIAN");
        UUID unknownAccount = UUID.randomUUID(); // inexistente o deshabilitada: no aparece en el directorio
        AssignCategoryCommand command = new AssignCategoryCommand(minute("ACTA-1"), category.id(), coach,
                List.of(evaluatorA, guardianOnly, unknownAccount), null);
        DomainException e = thrown(() -> service.assign(actor, player, command));
        assertThat(e.getCode()).isEqualTo(ErrorCode.PANEL_INVALID);
        assertThat(errorFields(e)).containsExactly("evaluatorIds", "evaluatorIds");
    }

    @Test
    void theHeadCoachMayAlsoSitOnThePanel() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        // El ERS describe el panel como entrenador titular, preparador físico y delegado del comité.
        AssignmentView view = service.assign(actor, player, new AssignCategoryCommand(minute("ACTA-1"), category.id(),
                coach, List.of(coach, evaluatorA, evaluatorC), null));
        assertThat(view.evaluatorIds()).hasSize(3);
    }

    @Test
    void panelRolesAreConfigurable() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        CommitteeDecisionService strict = new CommitteeDecisionService(committee, categories, staff, files,
                CommitteeSettings.of(List.of("EVALUATOR"), true, ZONE), audit);
        // evaluatorC solo tiene COMMITTEE, que esta configuración no admite.
        assertThat(errorFields(thrown(() -> strict.assign(actor, player, assignment())))).containsExactly("evaluatorIds");
    }

    @Test
    void theCategoryMustExistAndBeActive() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        assertThat(errorFields(thrown(() -> service.assign(actor, player, assignment("ACTA-1", UUID.randomUUID(), null)))))
                .containsExactly("categoryId");
        CategoryInfo inactive = categories.add("Vieja", 2000, 2001, 5, 0, false);
        assertThat(errorFields(thrown(() -> service.assign(actor, player, assignment("ACTA-1", inactive.id(), null)))))
                .containsExactly("categoryId");
    }

    @Test
    void theSameMinuteCannotDecideTheSamePlayerTwice() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        committee.minutes.add(new CommitteeFakes.Minute(player, minute("ACTA-1"), "REJECTED", "previo"));
        DomainException e = thrown(() -> service.assign(actor, player, assignment()));
        assertThat(errorFields(e)).containsExactly("minuteNumber");
    }

    // ---------- cupo ----------

    @Test
    void aFullCategoryIsRefusedUnlessTheCommitteeStatesAnExceptionReason() {
        CategoryInfo tiny = categories.add("Sub-10", 2016, 2016, 1, 1, true); // ya ocupada
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);

        DomainException e = thrown(() -> service.assign(actor, player, assignment("ACTA-1", tiny.id(), null)));
        assertThat(e.getCode()).isEqualTo(ErrorCode.CATEGORY_FULL);
        assertThat(e.getExtensions().get("capacity")).isEqualTo(1);
        assertThat(e.getExtensions().get("occupied")).isEqualTo(1);
        assertThat(committee.players.get(player)).isEqualTo(PlayerStatus.SUBMITTED);

        AssignmentView view = service.assign(actor, player, assignment("ACTA-1", tiny.id(), "  Hermano de un jugador actual  "));
        assertThat(view.capacityExceptionReason()).isEqualTo("Hermano de un jugador actual");
        assertThat(lastAudit().reason()).isEqualTo("Hermano de un jugador actual");
        assertThat(committee.players.get(player)).isEqualTo(PlayerStatus.CATEGORY_ASSIGNED);
    }

    @Test
    void theCapacityExceptionCanBeDisabled() {
        CategoryInfo tiny = categories.add("Sub-10", 2016, 2016, 1, 1, true);
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        CommitteeDecisionService noExceptions = serviceWith(false);
        assertThat(thrown(() -> noExceptions.assign(actor, player, assignment("ACTA-1", tiny.id(), "motivo"))).getCode())
                .isEqualTo(ErrorCode.CATEGORY_FULL);
    }

    @Test
    void theExceptionReasonIsIgnoredWhenThereIsRoom() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        AssignmentView view = service.assign(actor, player, assignment("ACTA-1", category.id(), "no hacía falta"));
        assertThat(view.capacityExceptionReason()).isNull();
    }

    @Test
    void seatsAreCountedAgainstTheCategoryAsPlayersAreAssigned() {
        CategoryInfo two = categories.add("Sub-14", 2012, 2013, 2, 0, true);
        UUID p1 = committee.addPlayer(PlayerStatus.SUBMITTED);
        UUID p2 = committee.addPlayer(PlayerStatus.SUBMITTED);
        UUID p3 = committee.addPlayer(PlayerStatus.SUBMITTED);
        service.assign(actor, p1, assignment("ACTA-1", two.id(), null));
        service.assign(actor, p2, assignment("ACTA-1", two.id(), null));
        assertThat(thrown(() -> service.assign(actor, p3, assignment("ACTA-1", two.id(), null))).getCode())
                .isEqualTo(ErrorCode.CATEGORY_FULL);
    }

    // ---------- volver a decidir ----------

    @Test
    void aNewDecisionClosesThePreviousProcessWithoutCountingItAgainstTheCapacity() {
        CategoryInfo single = categories.add("Sub-8", 2018, 2018, 1, 0, true);
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        committee.seedCurrent(player, single.id(), List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(committee.currentIn(single.id())).isEqualTo(1L); // la plaza del proceso anterior sigue contando

        AssignmentView view = service.assign(actor, player, assignment("ACTA-2", single.id(), null));

        assertThat(view.capacityExceptionReason()).isNull(); // no se tomó como cupo lleno
        assertThat(committee.currentAssignments(player)).isEqualTo(1L);
        assertThat(view.evaluatorIds()).hasSize(3);
        assertThat(committee.panels.get(player).contains(evaluatorA)).isTrue();
    }

    // ---------- rechazar ----------

    @Test
    void rejectingRecordsTheReasonAndMovesThePlayerToRejected() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);

        DecisionResult result = service.reject(actor, player,
                new RejectEnrollmentCommand(minute(" ACTA-5 "), "  No cumple el perfil técnico  "));

        assertThat(result.playerStatus()).isEqualTo(PlayerStatus.REJECTED);
        assertThat(result.minuteNumber()).isEqualTo("ACTA-5");
        assertThat(committee.players.get(player)).isEqualTo(PlayerStatus.REJECTED);
        assertThat(committee.minutes.get(0).decision).isEqualTo("REJECTED");
        assertThat(committee.minutes.get(0).reason).isEqualTo("No cumple el perfil técnico");
        assertThat(lastAudit().action()).isEqualTo("PLAYER_ENROLLMENT_REJECTED");
        assertThat(lastAudit().reason()).isEqualTo("No cumple el perfil técnico");
        assertThat(committee.currentAssignments(player)).isEqualTo(0L);
    }

    @Test
    void aRejectionNeedsAReasonAndAMinute() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        DomainException e = thrown(() -> service.reject(actor, player,
                new RejectEnrollmentCommand(new MinuteData("", null, List.of(), null), " ")));
        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).containsExactly("minuteNumber", "meetingDate", "attendees", "minuteFileId", "reason");
        assertThat(committee.players.get(player)).isEqualTo(PlayerStatus.SUBMITTED);
    }

    @Test
    void rejectingAPlayerWithAStaleAssignmentClosesIt() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        committee.seedCurrent(player, category.id(), List.of(evaluatorA, evaluatorB, evaluatorC));
        service.reject(actor, player, new RejectEnrollmentCommand(minute("ACTA-7"), "No hay cupo este año"));
        assertThat(committee.currentAssignments(player)).isEqualTo(0L);
        assertThat(committee.panels.containsKey(player)).isFalse();
    }

    // ---------- consulta ----------

    @Test
    void theCurrentAssignmentIsNotFoundWhenThePlayerHasNone() {
        UUID player = committee.addPlayer(PlayerStatus.SUBMITTED);
        assertThat(thrown(() -> service.currentAssignment(player)).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        service.assign(actor, player, assignment());
        assertThat(service.currentAssignment(player).categoryId()).isEqualTo(category.id());
    }

    // ---------- parámetros ----------

    @Test
    void settingsAreNormalizedAndRefuseNonsense() {
        CommitteeSettings settings = CommitteeSettings.of(List.of(" evaluator ", "COACH"), false, ZONE);
        assertThat(settings.panelRoles().equals(Set.of("EVALUATOR", "COACH"))).isTrue();
        assertThat(refused(List.of())).isTrue();
        assertThat(refused(List.of("GUARDIAN"))).isTrue();
        assertThat(refused(List.of("EVALUATOR", "INVENTADO"))).isTrue();
    }

    private static boolean refused(List<String> roles) {
        try {
            CommitteeSettings.of(roles, true, ZONE);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
