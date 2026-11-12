package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.api.DocumentRequirements;
import co.edu.unipamplona.canchatrack.payments.api.EnrollmentBilling;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Matrícula, activación y cobro mensual (DAD 3.2: E04 → E05 → E06). El cobro vive en payments y los documentos en
 * documents; aquí solo se decide cuándo el jugador avanza. Activar exige la matrícula saldada (la deuda se deriva de los
 * pagos, no se declara) y los documentos obligatorios completos y vigentes (RF-048, CA-02).
 */
@Service
public class RegistrationService {

    static final String ACTION_STARTED = "PLAYER_REGISTRATION_STARTED";
    static final String ACTION_ACTIVATED = "PLAYER_ACTIVATED";

    private final RegistrationStore store;
    private final EnrollmentBilling billing;
    private final DocumentRequirements documents;
    private final AuditWriter audit;

    public RegistrationService(RegistrationStore store, EnrollmentBilling billing, DocumentRequirements documents,
                               AuditWriter audit) {
        this.store = store;
        this.billing = billing;
        this.documents = documents;
        this.audit = audit;
    }

    /** CATEGORY_ASSIGNED → PENDING_REGISTRATION con el cargo de matrícula de la tarifa indicada. */
    @Transactional
    public RegistrationResult startRegistration(AdminActor actor, UUID playerId, UUID feeId, LocalDate dueDate) {
        requireStatus(playerId, PlayerStatus.CATEGORY_ASSIGNED, "El jugador debe tener categoría asignada para iniciar su matrícula.");
        UUID chargeId = billing.chargeEnrollment(playerId, feeId, dueDate, actor.id());
        store.transition(playerId, PlayerStatus.PENDING_REGISTRATION);
        audit.write(AuditRecord.builder(ACTION_STARTED, "player")
                .actor(actor.id(), AdminActor.ROLE)
                .entity(playerId)
                .before(AuditJson.of("status", PlayerStatus.CATEGORY_ASSIGNED))
                .after(AuditJson.of("status", PlayerStatus.PENDING_REGISTRATION, "chargeId", chargeId))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return new RegistrationResult(playerId, PlayerStatus.PENDING_REGISTRATION, chargeId);
    }

    /** PENDING_REGISTRATION → ACTIVE, solo con la matrícula saldada y los documentos obligatorios completos y vigentes. */
    @Transactional
    public RegistrationResult activate(AdminActor actor, UUID playerId) {
        requireStatus(playerId, PlayerStatus.PENDING_REGISTRATION, "El jugador no está pendiente de matrícula.");
        if (!billing.isEnrollmentSettled(playerId)) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT, "La matrícula del jugador aún tiene saldo pendiente.");
        }
        List<String> missing = documents.missingMandatory(playerId);
        if (!missing.isEmpty()) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "Faltan documentos obligatorios vigentes: " + String.join(", ", missing) + ".",
                    Map.of("missingDocuments", missing));
        }
        store.transition(playerId, PlayerStatus.ACTIVE);
        audit.write(AuditRecord.builder(ACTION_ACTIVATED, "player")
                .actor(actor.id(), AdminActor.ROLE)
                .entity(playerId)
                .before(AuditJson.of("status", PlayerStatus.PENDING_REGISTRATION))
                .after(AuditJson.of("status", PlayerStatus.ACTIVE))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return new RegistrationResult(playerId, PlayerStatus.ACTIVE, null);
    }

    /** Un cargo mensual por cada jugador activo; repetir la operación no duplica nada. */
    @Transactional
    public MonthlyBillingResult generateMonthly(AdminActor actor, UUID feeId, LocalDate period, LocalDate dueDate) {
        List<UUID> active = store.activePlayerIds();
        int created = billing.chargeMonthly(active, feeId, period, dueDate, actor.id());
        return new MonthlyBillingResult(active.size(), created);
    }

    private void requireStatus(UUID playerId, PlayerStatus expected, String detail) {
        PlayerStatus status = store.lockStatus(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        if (status != expected) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT, detail);
        }
    }
}
