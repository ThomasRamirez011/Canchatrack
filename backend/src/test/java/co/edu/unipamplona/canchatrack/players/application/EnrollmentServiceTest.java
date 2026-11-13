package co.edu.unipamplona.canchatrack.players.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.documents.api.ConsentKind;
import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments.Attachment;
import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments.Problem;
import co.edu.unipamplona.canchatrack.players.application.Fakes.FakeCalls;
import co.edu.unipamplona.canchatrack.players.application.Fakes.FakeDocuments;
import co.edu.unipamplona.canchatrack.players.application.Fakes.FakeGuardians;
import co.edu.unipamplona.canchatrack.players.application.Fakes.InMemoryEnrollments;
import co.edu.unipamplona.canchatrack.players.application.Fakes.InMemoryPlayers;
import co.edu.unipamplona.canchatrack.players.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.players.application.Fakes.RecordingConsents;
import co.edu.unipamplona.canchatrack.players.domain.CallInfo;
import co.edu.unipamplona.canchatrack.players.domain.CallStatus;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class EnrollmentServiceTest {

    private final FakeCalls calls = new FakeCalls();
    private final InMemoryPlayers players = new InMemoryPlayers();
    private final InMemoryEnrollments enrollments = new InMemoryEnrollments();
    private final FakeGuardians guardians = new FakeGuardians();
    private final RecordingConsents consents = new RecordingConsents();
    private final FakeDocuments documents = new FakeDocuments();
    private final RecordingAudit audit = new RecordingAudit();
    private EnrollmentService service;

    private final UUID userId = UUID.randomUUID();
    private UUID guardianId;

    @BeforeEach
    void setUp() {
        service = new EnrollmentService(calls, players, enrollments, guardians, consents, documents, audit,
                new EnrollmentSettings("v1"), Fakes.CLOCK);
        guardianId = guardians.register(userId);
    }

    private SubmitEnrollmentCommand command(UUID callId, String documentNumber) {
        return new SubmitEnrollmentCommand(callId, "Juan", "Pérez", "TI", documentNumber, LocalDate.of(2014, 3, 9),
                "Madre", true, true);
    }

    private EnrollmentReceipt submit(SubmitEnrollmentCommand command) {
        return service.submit(userId, command, null, "JUnit");
    }

    private static DomainException thrown(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            return e;
        }
        throw new AssertionError("se esperaba DomainException");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorFields(DomainException e) {
        return ((List<Map<String, String>>) e.getExtensions().get("errors")).stream().map(m -> m.get("field")).toList();
    }

    // ---------- camino feliz ----------

    @Test
    void filesTheEnrollmentCreatingThePlayerLinkingTheGuardianAndRecordingConsents() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));

        EnrollmentReceipt receipt = submit(command(call.id(), " ti-1001 "));

        assertThat(receipt.filingCode()).isEqualTo("RAD-000001");
        assertThat(receipt.playerStatus()).isEqualTo(PlayerStatus.SUBMITTED);
        assertThat(receipt.viaException()).isFalse();
        assertThat(players.byNumber("TI-1001").firstName()).isEqualTo("Juan"); // documento normalizado
        assertThat(players.isLinkedGuardian(receipt.playerId(), guardianId)).isTrue();
        assertThat(players.primaryGuardian.get(receipt.playerId())).isEqualTo(guardianId);
        assertThat(consents.entries).extracting(Fakes.RecordingConsents.Entry::kind)
                .containsExactly(ConsentKind.DATA_PROCESSING, ConsentKind.PARTICIPATION);
        assertThat(consents.entries).allSatisfy(e -> assertThat(e.versionText()).isEqualTo("v1"));
        assertThat(audit.inTransaction).extracting(AuditRecord::action).containsExactly("ENROLLMENT_SUBMITTED");
        assertThat(audit.independent).isEmpty();
    }

    @Test
    void theAuditSummaryCarriesNoPersonalData() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));

        submit(command(call.id(), "TI-1001"));

        String after = audit.inTransaction.get(0).afterJson();
        assertThat(after).doesNotContain("Juan").doesNotContain("Pérez").doesNotContain("TI-1001");
    }

    // ---------- convocatoria (RN-01, RF-044) ----------

    @Test
    void outsideTheCallWindowWithoutAnExceptionIsRejectedAndLeavesIndependentTrace() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, false));

        DomainException e = thrown(() -> submit(command(call.id(), "TI-1001")));

        assertThat(e.getCode()).isEqualTo(ErrorCode.ENROLLMENT_CLOSED);
        assertThat(players.rows).isEmpty();
        assertThat(audit.independent).singleElement().satisfies(r -> {
            assertThat(r.action()).isEqualTo("ENROLLMENT_REJECTED");
            assertThat(r.outcome()).isEqualTo(AuditRecord.Outcome.DENIED);
            assertThat(r.reason()).isEqualTo("CALL_CLOSED");
        });
        assertThat(audit.inTransaction).isEmpty();
    }

    @Test
    void anActiveExceptionalEnablementAllowsFilingOutsideTheWindow() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, false));
        calls.grantException(call.id(), guardianId);

        EnrollmentReceipt receipt = submit(command(call.id(), "TI-1001"));

        assertThat(receipt.viaException()).isTrue();
        assertThat(enrollments.rows.get(0).exceptionId()).isNotNull();
        assertThat(audit.inTransaction.get(0).afterJson()).contains("\"viaException\":true");
    }

    @Test
    void anExceptionGrantedToAnotherGuardianDoesNotHelp() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, false));
        calls.grantException(call.id(), UUID.randomUUID());

        assertThat(thrown(() -> submit(command(call.id(), "TI-1001"))).getCode()).isEqualTo(ErrorCode.ENROLLMENT_CLOSED);
    }

    @Test
    void cancelledCallsAreClosedAndDraftsDoNotExist() {
        CallInfo cancelled = calls.add(Fakes.call(CallStatus.CANCELLED, true));
        CallInfo draft = calls.add(Fakes.call(CallStatus.DRAFT, true));

        assertThat(thrown(() -> submit(command(cancelled.id(), "TI-1001"))).getCode()).isEqualTo(ErrorCode.ENROLLMENT_CLOSED);
        assertThat(thrown(() -> submit(command(draft.id(), "TI-1001"))).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(thrown(() -> submit(command(UUID.randomUUID(), "TI-1001"))).getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---------- actor y datos ----------

    @Test
    void onlyAccountsWithAGuardianRecordCanFile() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));

        DomainException e = thrown(() -> service.submit(UUID.randomUUID(), command(call.id(), "TI-1001"), null, null));

        assertThat(e.getCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN);
    }

    @Test
    void bothConsentsAreMandatory() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        SubmitEnrollmentCommand noConsent = new SubmitEnrollmentCommand(call.id(), "Juan", "Pérez", "TI", "TI-1001",
                LocalDate.of(2014, 3, 9), null, false, false);

        DomainException e = thrown(() -> submit(noConsent));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).contains("acceptDataProcessing", "acceptParticipation");
        assertThat(players.rows).isEmpty();
        assertThat(consents.entries).isEmpty();
    }

    @Test
    void invalidPlayerDataIsReportedAllAtOnce() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        SubmitEnrollmentCommand bad = new SubmitEnrollmentCommand(call.id(), "", "123", "XX", "a b", LocalDate.of(2999, 1, 1),
                "x".repeat(41), true, true);

        DomainException e = thrown(() -> submit(bad));

        assertThat(errorFields(e)).contains("player.firstName", "player.lastName", "player.documentType",
                "player.documentNumber", "player.birthDate", "relationship");
    }

    // ---------- jugador existente ----------

    @Test
    void aPlayerOfAnotherGuardianIsAGenericConflictThatRevealsNothing() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        players.seed("TI-1001", PlayerStatus.REJECTED, UUID.randomUUID());

        DomainException e = thrown(() -> submit(command(call.id(), "TI-1001")));

        assertThat(e.getCode()).isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT);
        assertThat(e.getMessage()).doesNotContain("TI-1001").doesNotContain("vinculad");
        assertThat(audit.independent).singleElement().satisfies(r -> assertThat(r.reason()).isEqualTo("PLAYER_NOT_LINKED"));
        assertThat(enrollments.rows).isEmpty();
    }

    @Test
    void aPlayerWithAProcessInProgressCannotBeFiledAgain() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        for (PlayerStatus status : List.of(PlayerStatus.SUBMITTED, PlayerStatus.ACTIVE, PlayerStatus.SUSPENDED,
                PlayerStatus.WITHDRAWN)) {
            players.seed("N-" + status.name(), status, guardianId);
            DomainException e = thrown(() -> submit(command(call.id(), "N-" + status.name())));
            assertThat(e.getCode()).as(status.name()).isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT);
        }
        assertThat(enrollments.rows).isEmpty();
    }

    @Test
    void aRejectedOrSeasonClosedPlayerOfTheSameGuardianCanBeFiledAgain() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        players.seed("TI-2001", PlayerStatus.REJECTED, guardianId);
        players.seed("TI-2002", PlayerStatus.SEASON_CLOSED, guardianId);

        EnrollmentReceipt first = submit(command(call.id(), "TI-2001"));
        EnrollmentReceipt second = submit(command(call.id(), "TI-2002"));

        assertThat(first.playerStatus()).isEqualTo(PlayerStatus.SUBMITTED);
        assertThat(second.playerId()).isNotEqualTo(first.playerId());
        assertThat(players.refiled).isEqualTo(2);
        assertThat(players.rows).hasSize(2); // se reutiliza el expediente (RN-20), no se crea otro
    }

    @Test
    void theSamePlayerCannotBeEnrolledTwiceInTheSameCall() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        var seeded = players.seed("TI-3001", PlayerStatus.REJECTED, guardianId);
        enrollments.insert(seeded.id(), call.id(), guardianId, null, userId); // inscripción previa en la misma convocatoria

        DomainException e = thrown(() -> submit(command(call.id(), "TI-3001")));

        assertThat(e.getCode()).isEqualTo(ErrorCode.PLAYER_STATE_CONFLICT);
        assertThat(audit.independent).singleElement().satisfies(r -> assertThat(r.reason()).isEqualTo("ALREADY_ENROLLED_IN_CALL"));
    }

    // ---------- documentos iniciales (CU-02, RF-006) ----------

    private SubmitEnrollmentCommand withDocuments(UUID callId, String documentNumber, List<Attachment> attachments) {
        return new SubmitEnrollmentCommand(callId, "Juan", "Pérez", "TI", documentNumber, LocalDate.of(2014, 3, 9), "Madre",
                true, true, attachments);
    }

    private static Attachment attachment() {
        return new Attachment(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 11, 1), null, null);
    }

    @Test
    void theAttachmentsAreCreatedForTheNewPlayerAndTheReceiptReportsWhatIsMissing() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        documents.missing = List.of("Registro civil");
        List<Attachment> attachments = List.of(attachment(), attachment());

        EnrollmentReceipt receipt = submit(withDocuments(call.id(), "TI-1001", attachments));

        assertThat(documents.checkedFor).containsExactly(userId);
        assertThat(documents.attachCalls).singleElement().satisfies(c -> {
            assertThat(c.userId()).isEqualTo(userId);
            assertThat(c.playerId()).isEqualTo(receipt.playerId());
            assertThat(c.attachments()).isEqualTo(attachments);
        });
        assertThat(receipt.attachedDocuments()).isEqualTo(2);
        assertThat(receipt.missingDocuments()).containsExactly("Registro civil");
        assertThat(audit.inTransaction.get(0).afterJson()).contains("\"attachedDocuments\":2");
    }

    @Test
    void aFilingWithoutAttachmentsIsAcceptedAndStillReportsTheMandatoryOnesPending() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        documents.missing = List.of("Certificado médico", "Registro civil");

        EnrollmentReceipt receipt = submit(command(call.id(), "TI-1001"));

        assertThat(receipt.attachedDocuments()).isZero();
        assertThat(receipt.missingDocuments()).containsExactly("Certificado médico", "Registro civil");
        assertThat(documents.attachCalls).singleElement().satisfies(c -> assertThat(c.attachments()).isEmpty());
    }

    @Test
    void anInvalidAttachmentIsReportedTogetherWithTheFormErrorsAndNothingIsWritten() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        documents.problems.add(new Problem("documents[0].fileId", "El archivo no existe."));
        SubmitEnrollmentCommand bad = new SubmitEnrollmentCommand(call.id(), "", "Pérez", "TI", "TI-1001",
                LocalDate.of(2014, 3, 9), null, true, true, List.of(attachment()));

        DomainException e = thrown(() -> submit(bad));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorFields(e)).contains("player.firstName", "documents[0].fileId");
        assertThat(players.rows).isEmpty();
        assertThat(enrollments.rows).isEmpty();
        assertThat(consents.entries).isEmpty();
        assertThat(documents.attachCalls).isEmpty();
    }

    @Test
    void aRejectedDocumentUploadOnAReusedRecordLeavesAnIndependentTraceAndFilesNothing() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        players.seed("TI-4001", PlayerStatus.REJECTED, guardianId);
        documents.attachFailure = new DomainException(ErrorCode.DOCUMENT_UPLOAD_NOT_ALLOWED, "sin ventana");

        DomainException e = thrown(() -> submit(withDocuments(call.id(), "TI-4001", List.of(attachment()))));

        assertThat(e.getCode()).isEqualTo(ErrorCode.DOCUMENT_UPLOAD_NOT_ALLOWED);
        assertThat(audit.independent).singleElement().satisfies(r -> {
            assertThat(r.action()).isEqualTo("ENROLLMENT_REJECTED");
            assertThat(r.reason()).isEqualTo("DOCUMENT_UPLOAD_NOT_ALLOWED");
        });
        assertThat(audit.inTransaction).isEmpty(); // la radicación no llegó a auditarse como exitosa
    }

    @Test
    void anyOtherAttachFailureIsPropagatedWithoutAnExtraTrace() {
        CallInfo call = calls.add(Fakes.call(CallStatus.PUBLISHED, true));
        documents.attachFailure = new DomainException(ErrorCode.VALIDATION_FAILED, null);

        DomainException e = thrown(() -> submit(withDocuments(call.id(), "TI-1001", List.of(attachment()))));

        assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(audit.independent).isEmpty();
    }

    @Test
    void theNineArgumentCommandMeansNoAttachmentsAndNullIsTreatedAsEmpty() {
        UUID callId = UUID.randomUUID();
        SubmitEnrollmentCommand plain = command(callId, "TI-1001");
        SubmitEnrollmentCommand nulls = withDocuments(callId, "TI-1001", null);

        assertThat(plain.documents()).isEmpty();
        assertThat(nulls.documents()).isEmpty();
    }
}
