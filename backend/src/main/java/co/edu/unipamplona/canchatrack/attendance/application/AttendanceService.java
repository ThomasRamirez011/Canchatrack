package co.edu.unipamplona.canchatrack.attendance.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.attendance.domain.AttendanceRules;
import co.edu.unipamplona.canchatrack.attendance.domain.FieldError;
import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.identity.api.PlayerData;
import co.edu.unipamplona.canchatrack.players.api.CategoryRoster;
import co.edu.unipamplona.canchatrack.players.api.RosterPlayer;
import co.edu.unipamplona.canchatrack.scheduling.api.EventDirectory;
import co.edu.unipamplona.canchatrack.scheduling.api.EventInfo;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Asistencia por sesión (CU-06). Las marcas llegan en lotes con clave de idempotencia (el dispositivo puede trabajar sin
 * conexión y reintentar): un reintento no duplica nada. Cerrar la sesión la bloquea para edición ordinaria y marca como
 * ausentes a quienes no tienen marca; después solo se corrige con justificación y rastro auditado (RN-15).
 */
@Service
public class AttendanceService {

    static final String ACTION_CLOSED = "ATTENDANCE_SESSION_CLOSED";
    static final String ACTION_CORRECTED = "ATTENDANCE_CORRECTED";
    private static final String ENTITY = "sports_event";

    private final AttendanceStore store;
    private final EventDirectory events;
    private final CategoryRoster roster;
    private final PlayerAccessPolicy access;
    private final AuditWriter audit;
    private final AttendanceTelemetry telemetry;

    public AttendanceService(AttendanceStore store, EventDirectory events, CategoryRoster roster, PlayerAccessPolicy access,
                             AuditWriter audit, AttendanceTelemetry telemetry) {
        this.store = store;
        this.events = events;
        this.roster = roster;
        this.access = access;
        this.audit = audit;
        this.telemetry = telemetry;
    }

    /** Plantilla de la categoría con la marca de cada jugador (coordinador, comité y entrenador del evento). */
    @Transactional(readOnly = true)
    public AttendanceView view(UUID userId, Set<String> roles, UUID eventId) {
        EventInfo event = events.find(eventId).orElseThrow(AttendanceService::eventNotFound);
        if (!roles.contains("COORDINATOR") && !roles.contains("COMMITTEE") && !userId.equals(event.headCoachId())) {
            throw eventNotFound();
        }
        List<RecordInfo> records = store.recordsOf(eventId);
        Map<UUID, RecordInfo> byPlayer = new LinkedHashMap<>();
        records.forEach(r -> byPlayer.put(r.playerId(), r));
        Set<UUID> ids = new LinkedHashSet<>(roster.activePlayerIds(event.categoryId()));
        ids.addAll(byPlayer.keySet());
        Map<UUID, RosterPlayer> people = roster.describe(ids);
        List<RosterEntry> entries = ids.stream().map(people::get).filter(p -> p != null)
                .sorted(Comparator.comparing(RosterPlayer::lastName).thenComparing(RosterPlayer::firstName))
                .map(p -> {
                    RecordInfo record = byPlayer.get(p.id());
                    return new RosterEntry(p.id(), p.code(), p.firstName(), p.lastName(),
                            record == null ? null : record.status(), record != null && record.excuseAccepted());
                }).toList();
        Instant closedAt = store.closedAt(eventId).orElse(null);
        return new AttendanceView(eventId, closedAt != null, closedAt, entries);
    }

    @Transactional
    public SyncResult sync(AttendanceActor actor, UUID eventId, String idempotencyKey, List<MarkInput> marks) {
        int batchSize = marks == null ? 0 : marks.size();
        return telemetry.traceSync(eventId, batchSize, () -> doSync(actor, eventId, idempotencyKey, marks));
    }

    private SyncResult doSync(AttendanceActor actor, UUID eventId, String idempotencyKey, List<MarkInput> marks) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, null);
        }
        List<FieldError> errors = new ArrayList<>();
        if (idempotencyKey.trim().length() > AttendanceRules.MAX_KEY) {
            errors.add(new FieldError("Idempotency-Key", "La clave no puede superar " + AttendanceRules.MAX_KEY + " caracteres."));
        }
        if (marks == null || marks.isEmpty()) {
            errors.add(new FieldError("records", "Debe enviar al menos una marca."));
        } else if (marks.size() > AttendanceRules.MAX_BATCH) {
            errors.add(new FieldError("records", "Un lote admite como máximo " + AttendanceRules.MAX_BATCH + " marcas."));
        }
        ValidationFailure.throwIfAny(errors);
        String key = idempotencyKey.trim();
        EventInfo event = manageable(actor, eventId);

        SyncBatch previous = store.findBatch(actor.id(), key).orElse(null);
        if (previous != null) {
            return replay(previous, eventId);
        }
        requireOpenForMarking(event);
        if (store.lockSession(eventId, false)) {
            throw new DomainException(ErrorCode.ATTENDANCE_SESSION_CLOSED, null);
        }
        UUID batchId = store.insertBatch(eventId, actor.id(), key).orElse(null);
        if (batchId == null) { // otro hilo procesó la misma clave entre la consulta y el alta
            return replay(store.findBatch(actor.id(), key).orElseThrow(), eventId);
        }
        Set<UUID> expected = new HashSet<>(roster.activePlayerIds(event.categoryId()));
        int accepted = 0;
        int duplicates = 0;
        List<Rejection> rejections = new ArrayList<>();
        for (MarkInput mark : marks) {
            List<String> problems = AttendanceRules.markViolations(mark.clientUuid(), mark.playerId(), mark.status(), mark.excuseAccepted());
            if (!problems.isEmpty()) {
                rejections.add(new Rejection(mark.clientUuid(), mark.playerId(), String.join(" ", problems)));
            } else if (!expected.contains(mark.playerId())) {
                rejections.add(new Rejection(mark.clientUuid(), mark.playerId(), "El jugador no está en la plantilla activa de la categoría."));
            } else if (store.insertRecord(eventId, mark.playerId(), mark.status().trim().toUpperCase(), mark.excuseAccepted(),
                    mark.clientUuid(), mark.clientRecordedAt(), actor.id(), batchId)) {
                accepted++;
            } else {
                duplicates++;
            }
        }
        store.finishBatch(batchId, marks.size(), accepted, duplicates, rejections.size());
        return new SyncResult(false, marks.size(), accepted, duplicates, rejections.size(), rejections);
    }

    @Transactional
    public CloseResult close(AttendanceActor actor, UUID eventId) {
        EventInfo event = manageable(actor, eventId);
        requireOpenForMarking(event);
        if (store.lockSession(eventId, true)) {
            throw new DomainException(ErrorCode.ATTENDANCE_SESSION_CLOSED, null);
        }
        Set<UUID> marked = new HashSet<>();
        store.recordsOf(eventId).forEach(r -> marked.add(r.playerId()));
        List<UUID> missing = roster.activePlayerIds(event.categoryId()).stream().filter(id -> !marked.contains(id)).toList();
        int autoAbsent = store.markAbsent(eventId, missing, actor.id());
        Instant closedAt = store.closeSession(eventId, actor.id())
                .orElseThrow(() -> new DomainException(ErrorCode.ATTENDANCE_SESSION_CLOSED, null));
        int total = marked.size() + autoAbsent;
        audit.write(AuditRecord.builder(ACTION_CLOSED, ENTITY)
                .actor(actor.id(), actor.auditRole())
                .entity(eventId)
                .after(Json.of("records", total, "autoAbsent", autoAbsent))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return new CloseResult(eventId, closedAt, autoAbsent, total);
    }

    /** RN-15: con la sesión cerrada, la asistencia solo se cambia con justificación y rastro. */
    @Transactional
    public RecordInfo correct(AttendanceActor actor, UUID eventId, CorrectionCommand command) {
        List<FieldError> errors = new ArrayList<>(AttendanceRules.correctionViolations(command.status(), command.excuseAccepted(), command.reason()));
        if (command.playerId() == null) {
            errors.add(new FieldError("playerId", "El jugador es obligatorio."));
        }
        ValidationFailure.throwIfAny(errors);
        manageable(actor, eventId);
        if (!store.isClosed(eventId)) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "La sesión sigue abierta: use el registro ordinario de marcas.");
        }
        RecordInfo before = store.findRecord(eventId, command.playerId())
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no tiene marca en esta sesión."));
        String newStatus = command.status().trim().toUpperCase();
        if (before.status().equals(newStatus) && before.excuseAccepted() == command.excuseAccepted()) {
            throw ValidationFailure.of("status", "La corrección no cambia la marca actual.");
        }
        store.correct(before.id(), before.status(), newStatus, command.excuseAccepted(), command.reason().trim(), actor.id());
        RecordInfo after = store.findRecord(eventId, command.playerId()).orElseThrow();
        audit.write(AuditRecord.builder(ACTION_CORRECTED, ENTITY)
                .actor(actor.id(), actor.auditRole())
                .entity(eventId)
                .before(Json.of("playerId", before.playerId(), "status", before.status(), "excuseAccepted", before.excuseAccepted()))
                .after(Json.of("playerId", after.playerId(), "status", after.status(), "excuseAccepted", after.excuseAccepted()))
                .reason(command.reason().trim())
                .origin(actor.ip(), actor.userAgent())
                .build());
        return after;
    }

    /** Asistencia acumulada del jugador. Sin acceso y sin existencia responden igual (404). */
    @Transactional(readOnly = true)
    public PlayerAttendanceSummary summary(UUID userId, Set<String> roles, UUID playerId) {
        if (!access.canView(userId, roles, playerId, PlayerData.ATTENDANCE)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe.");
        }
        return store.summaryOf(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
    }

    private SyncResult replay(SyncBatch batch, UUID eventId) {
        if (!batch.eventId().equals(eventId)) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED, null);
        }
        return new SyncResult(true, batch.received(), batch.accepted(), batch.duplicates(), batch.rejected(), List.of());
    }

    /** Coordinador: cualquier sesión. Entrenador: solo las de sus eventos (el resto responde 404). */
    private EventInfo manageable(AttendanceActor actor, UUID eventId) {
        EventInfo event = events.find(eventId).orElseThrow(AttendanceService::eventNotFound);
        if (!actor.isCoordinator() && !actor.id().equals(event.headCoachId())) {
            throw eventNotFound();
        }
        return event;
    }

    private void requireOpenForMarking(EventInfo event) {
        if (!"CONFIRMED".equals(event.status())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "Solo un evento confirmado admite asistencia.");
        }
        if (event.startAt().isAfter(store.now())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "La sesión aún no ha comenzado.");
        }
    }

    private static DomainException eventNotFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El evento no existe.");
    }
}
