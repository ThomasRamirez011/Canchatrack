package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.api.GuardianDirectory;
import co.edu.unipamplona.canchatrack.players.domain.CallRules;
import co.edu.unipamplona.canchatrack.players.domain.CallStatus;
import co.edu.unipamplona.canchatrack.players.domain.FieldError;
import co.edu.unipamplona.canchatrack.players.domain.SeasonStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * DAD CU-01 / RF-043, RF-044: el coordinador administra convocatorias de inscripción.
 *
 * <p>Ciclo: borrador → publicada → cancelada. Un borrador se edita libremente. Una convocatoria publicada ya fue vista
 * por los acudientes: solo admite cambiar el cierre y las instrucciones, y mover el cierre exige motivo (DAD:
 * reprogramación y cancelación se justifican). Cancelar no anula inscripciones ya radicadas; solo cierra la recepción.
 * Las habilitaciones excepcionales (RN-01) exigen motivo y vencen: no se editan ni se revocan, caducan.
 *
 * <p>Fuera de este incremento (pendientes del cliente): cupos por categoría (DP-01) y documentos obligatorios (DP-04),
 * que RF-043 menciona pero cuyo modelo aún no está definido.
 */
@Service
public class CallAdministrationService {

    static final String ACTION_CREATED = "CALL_CREATED";
    static final String ACTION_UPDATED = "CALL_UPDATED";
    static final String ACTION_PUBLISHED = "CALL_PUBLISHED";
    static final String ACTION_CANCELLED = "CALL_CANCELLED";
    static final String ACTION_EXCEPTION_GRANTED = "CALL_EXCEPTION_GRANTED";
    private static final String ENTITY = "call";

    private final CallStore calls;
    private final SeasonStore seasons;
    private final GuardianDirectory guardians;
    private final AuditWriter audit;
    private final CallSettings settings;

    public CallAdministrationService(CallStore calls, SeasonStore seasons, GuardianDirectory guardians,
                                     AuditWriter audit, CallSettings settings) {
        this.calls = calls;
        this.seasons = seasons;
        this.guardians = guardians;
        this.audit = audit;
        this.settings = settings;
    }

    @Transactional
    public CallDetail create(AdminActor actor, CreateCallCommand command) {
        List<FieldError> errors = new ArrayList<>(CallRules.callViolations(
                command.name(), command.startAt(), command.endAt(), command.instructions()));
        if (command.seasonId() == null) {
            errors.add(new FieldError("seasonId", "La temporada es obligatoria."));
        } else {
            SeasonInfo season = seasons.findById(command.seasonId()).orElse(null);
            if (season == null) {
                errors.add(new FieldError("seasonId", "La temporada no existe."));
            } else if (season.status() == SeasonStatus.CLOSED) {
                errors.add(new FieldError("seasonId", "La temporada está cerrada."));
            }
        }
        ValidationFailure.throwIfAny(errors);

        CallDetail call = calls.insert(command.seasonId(), command.name().trim(), command.startAt(), command.endAt(),
                blankToNull(command.instructions()), actor.id());
        audit.write(audit(ACTION_CREATED, actor, call.id())
                .after(summary(call))
                .build());
        return call;
    }

    @Transactional(readOnly = true)
    public List<CallDetail> list(UUID seasonId, CallStatus status) {
        return calls.find(seasonId, status);
    }

    @Transactional(readOnly = true)
    public CallDetail get(UUID callId) {
        return calls.findById(callId).orElseThrow(CallAdministrationService::notFound);
    }

    @Transactional
    public CallDetail update(AdminActor actor, UUID callId, UpdateCallCommand command) {
        CallDetail current = calls.findByIdForUpdate(callId).orElseThrow(CallAdministrationService::notFound);
        if (current.status() == CallStatus.CANCELLED) {
            throw stateConflict("Una convocatoria cancelada no se puede modificar.");
        }
        boolean published = current.status() == CallStatus.PUBLISHED;

        String name = command.name() == null ? current.name() : command.name().trim();
        Instant startAt = command.startAt() == null ? current.startAt() : command.startAt();
        Instant endAt = command.endAt() == null ? current.endAt() : command.endAt();
        String instructions = command.instructions() == null ? current.instructions() : blankToNull(command.instructions());

        List<FieldError> errors = new ArrayList<>();
        if (command.name() == null && command.startAt() == null && command.endAt() == null
                && command.instructions() == null) {
            errors.add(new FieldError("body", "Indique al menos un campo por modificar."));
        }
        if (published && !name.equals(current.name())) {
            errors.add(new FieldError("name", "El nombre no se puede cambiar en una convocatoria publicada."));
        }
        if (published && !startAt.equals(current.startAt())) {
            errors.add(new FieldError("startAt", "La apertura no se puede cambiar en una convocatoria publicada."));
        }
        errors.addAll(CallRules.callViolations(name, startAt, endAt, instructions));

        String reason = blankToNull(command.reason());
        boolean closingMoves = published && !endAt.equals(current.endAt());
        if (closingMoves || reason != null) { // obligatorio al mover el cierre; si se envía sin serlo, también debe ser válido
            CallRules.reasonViolations(errors, reason);
        }
        ValidationFailure.throwIfAny(errors);

        boolean unchanged = name.equals(current.name()) && startAt.equals(current.startAt())
                && endAt.equals(current.endAt()) && Objects.equals(instructions, current.instructions());
        if (unchanged) {
            return current;
        }
        calls.update(callId, name, startAt, endAt, instructions);
        CallDetail updated = calls.findById(callId).orElseThrow();
        audit.write(audit(ACTION_UPDATED, actor, callId)
                .before(summary(current))
                .after(summary(updated))
                .reason(reason == null ? null : reason.trim())
                .build());
        return updated;
    }

    @Transactional
    public CallDetail publish(AdminActor actor, UUID callId) {
        CallDetail current = calls.findByIdForUpdate(callId).orElseThrow(CallAdministrationService::notFound);
        if (current.status() != CallStatus.DRAFT) {
            throw stateConflict("Solo una convocatoria en borrador puede publicarse.");
        }
        if (!calls.publish(callId)) {
            throw ValidationFailure.of("endAt", "La fecha de cierre ya pasó: ajústela antes de publicar.");
        }
        CallDetail published = calls.findById(callId).orElseThrow();
        audit.write(audit(ACTION_PUBLISHED, actor, callId)
                .before(AuditJson.of("status", current.status()))
                .after(AuditJson.of("status", published.status(), "startAt", published.startAt(), "endAt", published.endAt()))
                .build());
        return published;
    }

    @Transactional
    public CallDetail cancel(AdminActor actor, UUID callId, String reason) {
        CallDetail current = calls.findByIdForUpdate(callId).orElseThrow(CallAdministrationService::notFound);
        List<FieldError> errors = new ArrayList<>();
        CallRules.reasonViolations(errors, reason);
        ValidationFailure.throwIfAny(errors);
        if (current.status() == CallStatus.CANCELLED || !calls.cancel(callId)) {
            throw stateConflict("La convocatoria ya está cancelada.");
        }
        CallDetail cancelled = calls.findById(callId).orElseThrow();
        audit.write(audit(ACTION_CANCELLED, actor, callId)
                .before(AuditJson.of("status", current.status()))
                .after(AuditJson.of("status", cancelled.status()))
                .reason(reason.trim())
                .build());
        return cancelled;
    }

    @Transactional
    public CallExceptionInfo grantException(AdminActor actor, UUID callId, GrantExceptionCommand command) {
        CallDetail call = calls.findByIdForUpdate(callId).orElseThrow(CallAdministrationService::notFound);
        if (call.status() != CallStatus.PUBLISHED) {
            throw stateConflict("Solo una convocatoria publicada admite habilitaciones excepcionales.");
        }
        List<FieldError> errors = new ArrayList<>();
        if (command.guardianId() == null) {
            errors.add(new FieldError("guardianId", "El acudiente es obligatorio."));
        } else if (!guardians.exists(command.guardianId())) {
            errors.add(new FieldError("guardianId", "El acudiente no existe."));
        }
        CallRules.reasonViolations(errors, command.reason());
        if (command.expiresAt() == null) {
            errors.add(new FieldError("expiresAt", "La fecha de vencimiento es obligatoria."));
        } else {
            Instant now = calls.now();
            if (!command.expiresAt().isAfter(now)) {
                errors.add(new FieldError("expiresAt", "El vencimiento debe ser posterior al momento actual."));
            } else if (command.expiresAt().isAfter(now.plus(settings.maxExceptionValidity()))) {
                errors.add(new FieldError("expiresAt",
                        "El vencimiento no puede superar " + settings.maxExceptionValidity().toDays() + " días."));
            }
        }
        ValidationFailure.throwIfAny(errors);

        CallExceptionInfo granted = calls.insertException(callId, command.guardianId(), command.reason().trim(),
                actor.id(), command.expiresAt());
        audit.write(AuditRecord.builder(ACTION_EXCEPTION_GRANTED, "call_exception")
                .actor(actor.id(), AdminActor.ROLE)
                .entity(granted.id())
                .after(AuditJson.of("callId", callId, "guardianId", granted.guardianId(),
                        "expiresAt", granted.expiresAt()))
                .reason(granted.reason())
                .origin(actor.ip(), actor.userAgent())
                .build());
        return granted;
    }

    @Transactional(readOnly = true)
    public List<CallExceptionInfo> exceptions(UUID callId) {
        calls.findById(callId).orElseThrow(CallAdministrationService::notFound);
        return calls.findExceptions(callId);
    }

    private static AuditRecord.Builder audit(String action, AdminActor actor, UUID callId) {
        return AuditRecord.builder(action, ENTITY)
                .actor(actor.id(), AdminActor.ROLE)
                .entity(callId)
                .origin(actor.ip(), actor.userAgent());
    }

    private static String summary(CallDetail call) {
        return AuditJson.of("seasonId", call.seasonId(), "name", call.name(), "startAt", call.startAt(),
                "endAt", call.endAt(), "status", call.status());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "La convocatoria no existe.");
    }

    private static DomainException stateConflict(String detail) {
        return new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, detail);
    }
}
