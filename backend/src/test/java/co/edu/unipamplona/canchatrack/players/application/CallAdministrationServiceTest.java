package co.edu.unipamplona.canchatrack.players.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.players.application.CallAdminFakes.InMemoryCallStore;
import co.edu.unipamplona.canchatrack.players.application.CallAdminFakes.InMemorySeasons;
import co.edu.unipamplona.canchatrack.players.application.Fakes.FakeGuardians;
import co.edu.unipamplona.canchatrack.players.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.players.domain.CallStatus;
import co.edu.unipamplona.canchatrack.players.domain.SeasonStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class CallAdministrationServiceTest {

    private static final Instant NOW = Fakes.NOW;

    private final InMemoryCallStore calls = new InMemoryCallStore();
    private final InMemorySeasons seasons = new InMemorySeasons();
    private final FakeGuardians guardians = new FakeGuardians();
    private final RecordingAudit audit = new RecordingAudit();
    private final AdminActor actor = new AdminActor(UUID.randomUUID(), null, "JUnit");
    private CallAdministrationService service;
    private SeasonInfo season;

    @BeforeEach
    void setUp() {
        service = new CallAdministrationService(calls, seasons, guardians, audit, new CallSettings(Duration.ofDays(30)));
        season = seasons.add(SeasonStatus.ACTIVE);
    }

    private CreateCallCommand draft(String name) {
        return new CreateCallCommand(season.id(), name, NOW.plusSeconds(86_400), NOW.plusSeconds(86_400 * 10),
                "Traer documento");
    }

    private CallDetail published() {
        return calls.put(season.id(), CallStatus.PUBLISHED, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400 * 5));
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

    // --- crear ---

    @Test
    void createsADraftAndLeavesAnAuditTrail() {
        CallDetail call = service.create(actor, draft("  Convocatoria 2027  "));

        assertThat(call.status()).isEqualTo(CallStatus.DRAFT);
        assertThat(call.name()).isEqualTo("Convocatoria 2027");
        assertThat(audit.inTransaction).hasSize(1);
        assertThat(lastAudit().action()).isEqualTo("CALL_CREATED");
        assertThat(lastAudit().actorId()).isEqualTo(actor.id());
        assertThat(lastAudit().actorRole()).isEqualTo("COORDINATOR");
        assertThat(lastAudit().entityId()).isEqualTo(call.id());
    }

    @Test
    void createReportsEveryFieldErrorTogether() {
        CreateCallCommand bad = new CreateCallCommand(UUID.randomUUID(), " ", NOW.plusSeconds(100), NOW, null);

        DomainException e = thrown(() -> service.create(actor, bad));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).contains("name", "endAt", "seasonId");
        assertThat(calls.calls.size()).isEqualTo(0);
        assertThat(audit.inTransaction).isEmpty();
    }

    @Test
    void aClosedSeasonDoesNotAcceptNewCalls() {
        SeasonInfo closed = seasons.add(SeasonStatus.CLOSED);

        DomainException e = thrown(() -> service.create(actor, new CreateCallCommand(closed.id(), "X",
                NOW.plusSeconds(10), NOW.plusSeconds(20), null)));

        assertThat(errorFields(e)).contains("seasonId");
    }

    @Test
    void theAuditSummaryStaysValidJsonWhenTheNameHasQuotes() {
        service.create(actor, draft("Convocatoria \"Élite\" \\ 2027"));

        assertThat(lastAudit().afterJson().contains("\\\"Élite\\\"")).isTrue();
    }

    // --- editar ---

    @Test
    void aDraftCanBeEditedFreely() {
        CallDetail call = service.create(actor, draft("Original"));
        Instant newStart = NOW.plusSeconds(3_600);
        Instant newEnd = NOW.plusSeconds(7_200);

        CallDetail updated = service.update(actor, call.id(),
                new UpdateCallCommand("Nuevo nombre", newStart, newEnd, "Otras instrucciones", null));

        assertThat(updated.name()).isEqualTo("Nuevo nombre");
        assertThat(updated.startAt()).isEqualTo(newStart);
        assertThat(updated.endAt()).isEqualTo(newEnd);
        assertThat(updated.instructions()).isEqualTo("Otras instrucciones");
        assertThat(lastAudit().action()).isEqualTo("CALL_UPDATED");
        assertThat(lastAudit().beforeJson().contains("Original")).isTrue();
        assertThat(lastAudit().afterJson().contains("Nuevo nombre")).isTrue();
    }

    @Test
    void anUpdateWithNothingToChangeIsRejected() {
        CallDetail call = service.create(actor, draft("X"));

        DomainException e = thrown(() -> service.update(actor, call.id(), new UpdateCallCommand(null, null, null, null, null)));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).contains("body");
    }

    @Test
    void anUpdateThatChangesNothingLeavesNoAuditTrail() {
        CallDetail call = service.create(actor, draft("X"));
        int before = audit.inTransaction.size();

        CallDetail same = service.update(actor, call.id(), new UpdateCallCommand("X", null, null, null, null));

        assertThat(same.id()).isEqualTo(call.id());
        assertThat(audit.inTransaction).hasSize(before);
    }

    @Test
    void emptyInstructionsClearThem() {
        CallDetail call = service.create(actor, draft("X"));

        CallDetail updated = service.update(actor, call.id(), new UpdateCallCommand(null, null, null, "  ", null));

        assertThat(updated.instructions()).isNull();
    }

    @Test
    void aPublishedCallKeepsItsNameAndOpeningButAllowsInstructions() {
        CallDetail call = published();

        DomainException e = thrown(() -> service.update(actor, call.id(),
                new UpdateCallCommand("Otro", NOW.minusSeconds(1), null, null, null)));
        assertThat(errorFields(e)).contains("name", "startAt");

        CallDetail updated = service.update(actor, call.id(), new UpdateCallCommand(null, null, null, "Nuevas", null));
        assertThat(updated.instructions()).isEqualTo("Nuevas");
    }

    @Test
    void movingTheClosingOfAPublishedCallRequiresAReason() {
        CallDetail call = published();
        Instant newEnd = NOW.plusSeconds(86_400 * 9);

        DomainException e = thrown(() -> service.update(actor, call.id(), new UpdateCallCommand(null, null, newEnd, null, null)));
        assertThat(errorFields(e)).contains("reason");
        assertThat(calls.calls.get(call.id()).endAt()).isEqualTo(call.endAt());

        CallDetail updated = service.update(actor, call.id(),
                new UpdateCallCommand(null, null, newEnd, null, "  Ampliación por solicitud del comité  "));
        assertThat(updated.endAt()).isEqualTo(newEnd);
        assertThat(lastAudit().reason()).isEqualTo("Ampliación por solicitud del comité");
    }

    @Test
    void theClosingMustStayAfterTheOpening() {
        CallDetail call = published();

        DomainException e = thrown(() -> service.update(actor, call.id(),
                new UpdateCallCommand(null, null, call.startAt(), null, "Cierre anticipado")));

        assertThat(errorFields(e)).contains("endAt");
    }

    @Test
    void aCancelledCallCannotBeModified() {
        CallDetail call = calls.put(season.id(), CallStatus.CANCELLED, NOW, NOW.plusSeconds(100));

        DomainException e = thrown(() -> service.update(actor, call.id(), new UpdateCallCommand("X", null, null, null, null)));

        assertThat(e.getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    // --- publicar y cancelar ---

    @Test
    void publishingMovesADraftToPublishedAndIsAudited() {
        CallDetail call = service.create(actor, draft("X"));

        CallDetail published = service.publish(actor, call.id());

        assertThat(published.status()).isEqualTo(CallStatus.PUBLISHED);
        assertThat(lastAudit().action()).isEqualTo("CALL_PUBLISHED");
        assertThat(lastAudit().beforeJson().contains("DRAFT")).isTrue();
        assertThat(lastAudit().afterJson().contains("PUBLISHED")).isTrue();
    }

    @Test
    void aCallWhoseClosingAlreadyPassedCannotBePublished() {
        CallDetail call = calls.put(season.id(), CallStatus.DRAFT, NOW.minusSeconds(200), NOW.minusSeconds(100));

        DomainException e = thrown(() -> service.publish(actor, call.id()));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).contains("endAt");
        assertThat(calls.calls.get(call.id()).status()).isEqualTo(CallStatus.DRAFT);
        assertThat(audit.inTransaction).isEmpty();
    }

    @Test
    void onlyADraftCanBePublished() {
        CallDetail call = published();

        assertThat(thrown(() -> service.publish(actor, call.id())).getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void cancellingNeedsAReasonAndKeepsIt() {
        CallDetail call = published();

        DomainException e = thrown(() -> service.cancel(actor, call.id(), "  "));
        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(calls.calls.get(call.id()).status()).isEqualTo(CallStatus.PUBLISHED);

        CallDetail cancelled = service.cancel(actor, call.id(), " Cupos agotados ");
        assertThat(cancelled.status()).isEqualTo(CallStatus.CANCELLED);
        assertThat(lastAudit().action()).isEqualTo("CALL_CANCELLED");
        assertThat(lastAudit().reason()).isEqualTo("Cupos agotados");
    }

    @Test
    void aCallCannotBeCancelledTwice() {
        CallDetail call = service.create(actor, draft("X"));
        service.cancel(actor, call.id(), "Error de captura");

        assertThat(thrown(() -> service.cancel(actor, call.id(), "Otra vez")).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }

    @Test
    void anUnknownCallIsNotFoundInEveryOperation() {
        UUID unknown = UUID.randomUUID();
        GrantExceptionCommand grant = new GrantExceptionCommand(UUID.randomUUID(), "Motivo", NOW.plusSeconds(100));

        assertThat(thrown(() -> service.get(unknown)).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(thrown(() -> service.update(actor, unknown, new UpdateCallCommand("X", null, null, null, null))).getCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(thrown(() -> service.publish(actor, unknown)).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(thrown(() -> service.cancel(actor, unknown, "Motivo")).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(thrown(() -> service.grantException(actor, unknown, grant)).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(thrown(() -> service.exceptions(unknown)).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void listingFiltersBySeasonAndStatus() {
        SeasonInfo other = seasons.add(SeasonStatus.PLANNED);
        CallDetail a = calls.put(season.id(), CallStatus.PUBLISHED, NOW, NOW.plusSeconds(10));
        calls.put(season.id(), CallStatus.DRAFT, NOW, NOW.plusSeconds(10));
        calls.put(other.id(), CallStatus.PUBLISHED, NOW, NOW.plusSeconds(10));

        assertThat(service.list(null, null)).hasSize(3);
        assertThat(service.list(season.id(), null)).hasSize(2);
        assertThat(service.list(season.id(), CallStatus.PUBLISHED)).hasSize(1);
        assertThat(service.list(season.id(), CallStatus.PUBLISHED).get(0).id()).isEqualTo(a.id());
    }

    // --- habilitaciones excepcionales ---

    @Test
    void grantsAnExceptionWithReasonAndExpiryAndAuditsIt() {
        CallDetail call = published();
        UUID guardianId = guardians.register(UUID.randomUUID());
        Instant expiresAt = NOW.plusSeconds(86_400 * 3);

        CallExceptionInfo granted = service.grantException(actor, call.id(),
                new GrantExceptionCommand(guardianId, "  Falla del portal el último día  ", expiresAt));

        assertThat(granted.guardianId()).isEqualTo(guardianId);
        assertThat(granted.grantedBy()).isEqualTo(actor.id());
        assertThat(granted.reason()).isEqualTo("Falla del portal el último día");
        assertThat(granted.active()).isTrue();
        assertThat(lastAudit().action()).isEqualTo("CALL_EXCEPTION_GRANTED");
        assertThat(lastAudit().reason()).isEqualTo("Falla del portal el último día");
        assertThat(service.exceptions(call.id())).hasSize(1);
    }

    @Test
    void anExceptionNeedsAnExistingGuardianAReasonAndAFutureExpiry() {
        CallDetail call = published();

        DomainException e = thrown(() -> service.grantException(actor, call.id(),
                new GrantExceptionCommand(UUID.randomUUID(), " ", NOW)));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).contains("guardianId", "reason", "expiresAt");
        assertThat(calls.exceptions).isEmpty();
        assertThat(audit.inTransaction).isEmpty();
    }

    @Test
    void anExceptionCannotOutliveTheConfiguredMaximum() {
        CallDetail call = published();
        UUID guardianId = guardians.register(UUID.randomUUID());

        DomainException e = thrown(() -> service.grantException(actor, call.id(),
                new GrantExceptionCommand(guardianId, "Motivo", NOW.plus(Duration.ofDays(31)))));
        assertThat(errorFields(e)).contains("expiresAt");

        CallExceptionInfo atTheLimit = service.grantException(actor, call.id(),
                new GrantExceptionCommand(guardianId, "Motivo", NOW.plus(Duration.ofDays(30))));
        assertThat(atTheLimit.active()).isTrue();
    }

    @Test
    void exceptionsOnlyApplyToPublishedCalls() {
        UUID guardianId = guardians.register(UUID.randomUUID());
        GrantExceptionCommand grant = new GrantExceptionCommand(guardianId, "Motivo", NOW.plusSeconds(100));
        CallDetail draft = service.create(actor, draft("X"));
        CallDetail cancelled = calls.put(season.id(), CallStatus.CANCELLED, NOW, NOW.plusSeconds(100));

        assertThat(thrown(() -> service.grantException(actor, draft.id(), grant)).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
        assertThat(thrown(() -> service.grantException(actor, cancelled.id(), grant)).getCode())
                .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
    }
}
