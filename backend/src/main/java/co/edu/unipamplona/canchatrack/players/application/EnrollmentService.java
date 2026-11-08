package co.edu.unipamplona.canchatrack.players.application;

import java.net.InetAddress;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.api.ConsentKind;
import co.edu.unipamplona.canchatrack.documents.api.ConsentRecorder;
import co.edu.unipamplona.canchatrack.identity.api.GuardianDirectory;
import co.edu.unipamplona.canchatrack.players.domain.Admission;
import co.edu.unipamplona.canchatrack.players.domain.CallInfo;
import co.edu.unipamplona.canchatrack.players.domain.CallStatus;
import co.edu.unipamplona.canchatrack.players.domain.FieldError;
import co.edu.unipamplona.canchatrack.players.domain.PlayerDataRules;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentNumber;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

/**
 * UC-01 / DAD CU-02: el acudiente radica la inscripción de un jugador.
 *
 * <p>Reglas: convocatoria vigente o habilitación excepcional (RN-01, RF-044); acudiente registrado (RN-02);
 * consentimiento de datos y autorización de participación (RF-011); un jugador sin proceso en curso. Un intento
 * rechazado deja rastro de auditoría aunque la transacción se revierta (PA-01). Para no revelar qué documentos existen,
 * los conflictos con un jugador que no es del acudiente responden igual que los demás (409 genérico).
 *
 * <p>Pendiente de otros incrementos: adjuntar documentos (documents), notificar al acudiente y a la coordinación
 * (notifications) y evento {@code EnrollmentSubmitted}.
 */
@Service
public class EnrollmentService {

    static final String ACTION_SUBMITTED = "ENROLLMENT_SUBMITTED";
    static final String ACTION_REJECTED = "ENROLLMENT_REJECTED";
    private static final int MAX_RELATIONSHIP = 40;
    private static final String CONFLICT_DETAIL =
            "No es posible radicar la inscripción con los datos suministrados. Comuníquese con la coordinación.";

    private final CallCatalog calls;
    private final PlayerStore players;
    private final EnrollmentStore enrollments;
    private final GuardianDirectory guardians;
    private final ConsentRecorder consents;
    private final AuditWriter audit;
    private final EnrollmentSettings settings;
    private final Clock clock;

    public EnrollmentService(CallCatalog calls, PlayerStore players, EnrollmentStore enrollments,
                             GuardianDirectory guardians, ConsentRecorder consents, AuditWriter audit,
                             EnrollmentSettings settings, Clock clock) {
        this.calls = calls;
        this.players = players;
        this.enrollments = enrollments;
        this.guardians = guardians;
        this.consents = consents;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public EnrollmentReceipt submit(UUID userId, SubmitEnrollmentCommand command, InetAddress ip, String userAgent) {
        UUID guardianId = guardians.guardianIdOf(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.AUTH_FORBIDDEN, null));

        validate(command);
        IdDocumentType type = IdDocumentType.parse(command.documentType()).orElseThrow();
        String number = IdDocumentNumber.normalize(command.documentNumber()).orElseThrow();

        CallInfo call = calls.findById(command.callId())
                .filter(found -> found.status() != CallStatus.DRAFT)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "La convocatoria no existe."));
        Admission admission = Admission.decide(call, calls.activeExceptionId(call.id(), guardianId));
        if (!admission.admitted()) {
            reject(userId, call.status() == CallStatus.CANCELLED ? "CALL_CANCELLED" : "CALL_CLOSED", null, ip, userAgent);
            throw new DomainException(ErrorCode.ENROLLMENT_CLOSED, null);
        }

        PlayerRecord player = resolvePlayer(userId, guardianId, command, type, number, ip, userAgent);

        EnrollmentRecord enrollment;
        try {
            enrollment = enrollments.insert(player.id(), call.id(), guardianId, admission.exceptionId(), userId);
        } catch (DuplicateEnrollmentException e) {
            reject(userId, "ALREADY_ENROLLED_IN_CALL", player.id(), ip, userAgent);
            throw conflict();
        }

        consents.recordAuthenticatedAcceptance(guardianId, player.id(), ConsentKind.DATA_PROCESSING, settings.consentVersion());
        consents.recordAuthenticatedAcceptance(guardianId, player.id(), ConsentKind.PARTICIPATION, settings.consentVersion());

        boolean viaException = admission.exceptionId() != null;
        audit.write(AuditRecord.builder(ACTION_SUBMITTED, "enrollment")
                .actor(userId, "GUARDIAN")
                .entity(enrollment.id())
                .after("{\"filingCode\":\"" + enrollment.filingCode() + "\",\"callId\":\"" + call.id()
                        + "\",\"playerId\":\"" + player.id() + "\",\"viaException\":" + viaException + "}")
                .origin(ip, userAgent)
                .build());

        return new EnrollmentReceipt(enrollment.id(), enrollment.filingCode(), player.id(), player.code(),
                PlayerStatus.SUBMITTED, call.id(), enrollment.submittedAt(), viaException);
    }

    private PlayerRecord resolvePlayer(UUID userId, UUID guardianId, SubmitEnrollmentCommand command,
                                       IdDocumentType type, String number, InetAddress ip, String userAgent) {
        var existing = players.findByDocumentForUpdate(type, number);
        if (existing.isPresent()) {
            PlayerRecord found = existing.get();
            if (!players.isLinkedGuardian(found.id(), guardianId)) {
                reject(userId, "PLAYER_NOT_LINKED", found.id(), ip, userAgent);
                throw conflict();
            }
            if (!found.status().allowsNewEnrollment()) {
                reject(userId, "ENROLLMENT_IN_PROGRESS_OR_CLOSED", found.id(), ip, userAgent);
                throw conflict();
            }
            players.markRefiled(found.id());
            return new PlayerRecord(found.id(), found.code(), PlayerStatus.SUBMITTED);
        }
        PlayerRecord created;
        try {
            created = players.insert(command.firstName().trim(), command.lastName().trim(), type, number,
                    command.birthDate(), userId);
        } catch (DuplicatePlayerDocumentException e) {
            reject(userId, "PLAYER_DOCUMENT_RACE", null, ip, userAgent);
            throw conflict();
        }
        String relationship = command.relationship() == null || command.relationship().isBlank()
                ? null : command.relationship().trim();
        players.linkGuardian(created.id(), guardianId, relationship, true, userId);
        return created;
    }

    private void validate(SubmitEnrollmentCommand command) {
        List<FieldError> errors = new java.util.ArrayList<>(PlayerDataRules.violations(
                command.firstName(), command.lastName(), command.documentType(), command.documentNumber(),
                command.birthDate(), LocalDate.now(clock)));
        if (command.callId() == null) {
            errors.add(new FieldError("callId", "La convocatoria es obligatoria."));
        }
        if (command.relationship() != null && command.relationship().trim().length() > MAX_RELATIONSHIP) {
            errors.add(new FieldError("relationship", "El parentesco no puede superar " + MAX_RELATIONSHIP + " caracteres."));
        }
        if (!command.acceptDataProcessing()) {
            errors.add(new FieldError("acceptDataProcessing", "Debe aceptar el tratamiento de datos personales."));
        }
        if (!command.acceptParticipation()) {
            errors.add(new FieldError("acceptParticipation", "Debe otorgar la autorización de participación."));
        }
        if (!errors.isEmpty()) {
            List<Map<String, String>> body = errors.stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
            throw new DomainException(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", body));
        }
    }

    /** Rastro del intento rechazado: se confirma en una transacción aparte, porque esta se revertirá. */
    private void reject(UUID userId, String reason, UUID playerId, InetAddress ip, String userAgent) {
        audit.writeIndependent(AuditRecord.builder(ACTION_REJECTED, "enrollment")
                .actor(userId, "GUARDIAN")
                .entity(null)
                .outcome(AuditRecord.Outcome.DENIED)
                .reason(reason)
                .after(playerId == null ? null : "{\"playerId\":\"" + playerId + "\"}")
                .origin(ip, userAgent)
                .build());
    }

    private static DomainException conflict() {
        return new DomainException(ErrorCode.PLAYER_STATE_CONFLICT, CONFLICT_DETAIL);
    }
}
