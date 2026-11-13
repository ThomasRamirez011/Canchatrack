package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.api.StoredFileDirectory;
import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.identity.api.StaffDirectory;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.players.domain.CommitteeRules;
import co.edu.unipamplona.canchatrack.players.domain.FieldError;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Decisión del Comité Técnico sobre una inscripción radicada (DDS UC-02, transiciones T-01 y T-02; RN-03, RF-045).
 *
 * <p>Asignar: el expediente pasa a {@code CATEGORY_ASSIGNED} con categoría, entrenador titular y exactamente tres
 * evaluadores, respaldado por un acta. Rechazar: pasa a {@code REJECTED} con motivo. En ambos casos se bloquea el
 * expediente ({@code SELECT … FOR UPDATE}) y solo procede desde {@code SUBMITTED} o {@code COMMITTEE_PENDING}; repetir la
 * misma solicitud responde 409. Al decidir de nuevo sobre un expediente que vuelve a radicarse se cierra la categoría y
 * el panel del proceso anterior (el historial se conserva, RN-20).
 *
 * <p>Los valores (roles admitidos en el panel, excepción de cupo) son parametrizables hasta que el club responda DP-01.
 *
 * <p>Aviso del resultado (RF-047): al decidir se encola un correo para los acudientes con vínculo vigente, en la misma
 * transacción. El de la asignación nombra la categoría y el siguiente paso; el del rechazo es genérico y <b>no</b>
 * incluye el motivo ni nada del acta (puede tener datos sensibles, RN-17): el detalle se trata directamente con el club.
 * Quién integra el panel tampoco viaja por correo.
 */
@Service
public class CommitteeDecisionService {

    static final String ACTION_ASSIGNED = "PLAYER_CATEGORY_ASSIGNED";
    static final String ACTION_REJECTED = "PLAYER_ENROLLMENT_REJECTED";
    public static final String NOTICE_ASSIGNED = "COMMITTEE_ASSIGNED";
    public static final String NOTICE_REJECTED = "COMMITTEE_REJECTED";
    private static final String ENTITY = "player";
    private static final String COACH_ROLE = "COACH";

    private final CommitteeStore committee;
    private final CategoryStore categories;
    private final StaffDirectory staff;
    private final StoredFileDirectory files;
    private final CommitteeSettings settings;
    private final AuditWriter audit;
    private final GuardianContacts guardians;
    private final NotificationQueue queue;

    public CommitteeDecisionService(CommitteeStore committee, CategoryStore categories, StaffDirectory staff,
                                    StoredFileDirectory files, CommitteeSettings settings, AuditWriter audit,
                                    GuardianContacts guardians, NotificationQueue queue) {
        this.committee = committee;
        this.categories = categories;
        this.staff = staff;
        this.files = files;
        this.settings = settings;
        this.audit = audit;
        this.guardians = guardians;
        this.queue = queue;
    }

    @Transactional
    public AssignmentView assign(CommitteeActor actor, UUID playerId, AssignCategoryCommand command) {
        PlayerRecord player = lockDecidable(playerId);

        List<FieldError> errors = new ArrayList<>(minuteErrors(command.minute()));
        errors.addAll(CommitteeRules.assignmentViolations(command.categoryId(), command.headCoachId(),
                command.evaluatorIds(), command.capacityExceptionReason()));
        errors.addAll(roleErrors(command));
        throwIfAny(errors);

        // Se libera la plaza del proceso anterior antes de contar el cupo: no debe contarse contra sí mismo.
        committee.closeCurrentAssignment(playerId);

        CategoryInfo category = categories.findByIdForUpdate(command.categoryId()).orElse(null);
        if (category == null) {
            throw ValidationFailure.of("categoryId", "La categoría no existe.");
        }
        if (!category.active()) {
            throw ValidationFailure.of("categoryId", "La categoría «" + category.name() + "» no está activa.");
        }
        String exceptionReason = blankToNull(command.capacityExceptionReason());
        boolean full = category.occupied() >= category.capacity();
        if (full && (exceptionReason == null || !settings.allowCapacityException())) {
            throw new DomainException(ErrorCode.CATEGORY_FULL,
                    "La categoría «" + category.name() + "» no tiene cupo"
                            + (settings.allowCapacityException()
                            ? "; para asignar igualmente indique el motivo de la excepción (capacityExceptionReason)." : "."),
                    Map.of("categoryId", category.id().toString(), "capacity", category.capacity(),
                            "occupied", category.occupied()));
        }
        // El motivo solo se conserva si realmente se excede el cupo.
        String storedReason = full ? exceptionReason : null;

        UUID minuteId = insertMinute(player.id(), command.minute(), "ASSIGNED", null, actor);
        committee.insertCategoryAssignment(player.id(), category.id(), command.headCoachId(), minuteId, storedReason, actor.id());
        for (UUID evaluatorId : command.evaluatorIds()) {
            committee.insertEvaluatorAssignment(player.id(), evaluatorId, minuteId, actor.id());
        }
        transition(player, PlayerStatus.CATEGORY_ASSIGNED);

        audit.write(audit(ACTION_ASSIGNED, actor, player.id())
                .before(AuditJson.of("status", player.status()))
                .after(AuditJson.of("status", PlayerStatus.CATEGORY_ASSIGNED, "categoryId", category.id(),
                        "headCoachId", command.headCoachId(),
                        "evaluatorIds", command.evaluatorIds().stream().map(UUID::toString).collect(Collectors.joining(",")),
                        "minuteNumber", command.minute().minuteNumber().trim(), "capacityExceeded", full))
                .reason(storedReason)
                .build());
        notifyGuardians(NOTICE_ASSIGNED, "Categoría asignada", player.id(), minuteId,
                Map.of("categoryName", category.name()));
        return committee.findCurrentAssignment(player.id()).orElseThrow();
    }

    @Transactional
    public DecisionResult reject(CommitteeActor actor, UUID playerId, RejectEnrollmentCommand command) {
        PlayerRecord player = lockDecidable(playerId);

        List<FieldError> errors = new ArrayList<>(minuteErrors(command.minute()));
        errors.addAll(CommitteeRules.rejectionViolations(command.reason()));
        throwIfAny(errors);

        committee.closeCurrentAssignment(playerId);
        String reason = command.reason().trim();
        UUID minuteId = insertMinute(player.id(), command.minute(), "REJECTED", reason, actor);
        transition(player, PlayerStatus.REJECTED);

        audit.write(audit(ACTION_REJECTED, actor, player.id())
                .before(AuditJson.of("status", player.status()))
                .after(AuditJson.of("status", PlayerStatus.REJECTED, "minuteNumber", command.minute().minuteNumber().trim()))
                .reason(reason)
                .build());
        notifyGuardians(NOTICE_REJECTED, "Resultado de la inscripción", player.id(), minuteId, Map.of());
        return new DecisionResult(player.id(), PlayerStatus.REJECTED, minuteId, command.minute().minuteNumber().trim());
    }

    @Transactional(readOnly = true)
    public AssignmentView currentAssignment(UUID playerId) {
        return committee.findCurrentAssignment(playerId).orElseThrow(() -> new DomainException(
                ErrorCode.RESOURCE_NOT_FOUND, "El jugador no tiene una categoría asignada vigente."));
    }

    // ---------- validaciones ----------

    private PlayerRecord lockDecidable(UUID playerId) {
        PlayerRecord player = committee.lockPlayer(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        if (player.status() != PlayerStatus.SUBMITTED && player.status() != PlayerStatus.COMMITTEE_PENDING) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "El expediente no está pendiente de decisión del comité.");
        }
        return player;
    }

    private List<FieldError> minuteErrors(MinuteData minute) {
        List<FieldError> errors = new ArrayList<>(CommitteeRules.minuteViolations(
                minute.minuteNumber(), minute.meetingDate(), minute.attendees(), minute.minuteFileId()));
        LocalDate meetingDate = minute.meetingDate();
        if (meetingDate != null && meetingDate.isAfter(committee.today(settings.zone()))) {
            errors.add(new FieldError("meetingDate", "La fecha de la reunión no puede ser futura."));
        }
        if (minute.minuteFileId() != null && !files.exists(minute.minuteFileId())) {
            errors.add(new FieldError("minuteFileId", "El archivo del acta no existe."));
        }
        return errors;
    }

    /** El entrenador titular debe tener el rol COACH y los evaluadores un rol admitido en el panel; todos con cuenta activa. */
    private List<FieldError> roleErrors(AssignCategoryCommand command) {
        List<FieldError> errors = new ArrayList<>();
        Set<UUID> ids = new LinkedHashSet<>();
        if (command.headCoachId() != null) {
            ids.add(command.headCoachId());
        }
        if (command.evaluatorIds() != null) {
            command.evaluatorIds().stream().filter(java.util.Objects::nonNull).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            return errors;
        }
        Map<UUID, Set<String>> roles = staff.activeRolesOf(ids);
        if (command.headCoachId() != null && !roles.getOrDefault(command.headCoachId(), Set.of()).contains(COACH_ROLE)) {
            errors.add(new FieldError(CommitteeRules.FIELD_HEAD_COACH,
                    "El entrenador titular debe ser una cuenta activa con rol de entrenador."));
        }
        if (command.evaluatorIds() != null) {
            for (UUID evaluatorId : new LinkedHashSet<>(command.evaluatorIds())) {
                if (evaluatorId == null) {
                    continue;
                }
                Set<String> evaluatorRoles = roles.getOrDefault(evaluatorId, Set.of());
                if (evaluatorRoles.stream().noneMatch(settings.panelRoles()::contains)) {
                    errors.add(new FieldError(CommitteeRules.FIELD_EVALUATORS,
                            "El usuario " + evaluatorId + " no es una cuenta activa con un rol admitido en el panel ("
                                    + String.join(", ", settings.panelRoles().stream().sorted().toList()) + ")."));
                }
            }
        }
        return errors;
    }

    /** Si algún error es del panel se informa PANEL_INVALID; el cuerpo lleva todos los errores acumulados. */
    private static void throwIfAny(List<FieldError> errors) {
        if (errors.isEmpty()) {
            return;
        }
        boolean panel = errors.stream().anyMatch(e -> CommitteeRules.isPanelField(e.field()));
        throw ValidationFailure.of(panel ? ErrorCode.PANEL_INVALID : ErrorCode.VALIDATION_FAILED, errors);
    }

    // ---------- escritura ----------

    private UUID insertMinute(UUID playerId, MinuteData minute, String decision, String reason, CommitteeActor actor) {
        MinuteData normalized = new MinuteData(minute.minuteNumber().trim(), minute.meetingDate(),
                minute.attendees().stream().map(String::trim).toList(), minute.minuteFileId());
        try {
            return committee.insertMinute(playerId, normalized, decision, reason, actor.id());
        } catch (DuplicateMinuteException e) {
            throw ValidationFailure.of("minuteNumber", "Ya existe una decisión de este jugador en el acta "
                    + normalized.minuteNumber() + ".");
        }
    }

    /** Un aviso por decisión (el ámbito es el acta de la decisión): repetir la petición no duplica el correo. */
    private void notifyGuardians(String type, String subject, UUID playerId, UUID minuteId, Map<String, String> extra) {
        Set<UUID> recipients = guardians.guardianUserIdsOf(List.of(playerId));
        if (recipients.isEmpty()) {
            return;
        }
        Map<String, String> payload = new java.util.LinkedHashMap<>(extra);
        payload.put("playerName", committee.playerName(playerId));
        queue.enqueue(type, recipients, subject, payload, ENTITY, playerId, "decision:" + minuteId);
    }

    private void transition(PlayerRecord player, PlayerStatus target) {
        if (!committee.decide(player.id(), target)) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT, "El expediente cambió de estado; vuelva a consultarlo.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AuditRecord.Builder audit(String action, CommitteeActor actor, UUID playerId) {
        return AuditRecord.builder(action, ENTITY)
                .actor(actor.id(), CommitteeActor.ROLE)
                .entity(playerId)
                .origin(actor.ip(), actor.userAgent());
    }
}
