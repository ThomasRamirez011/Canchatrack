package co.edu.unipamplona.canchatrack.payments.application;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.payments.api.EnrollmentBilling;
import co.edu.unipamplona.canchatrack.payments.domain.FieldError;
import co.edu.unipamplona.canchatrack.payments.domain.PaymentRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Generación de cargos (V12): se persisten con el monto de la tarifa de ese momento, de modo que cambiar la tarifa no
 * reescribe la deuda histórica. Idempotente por jugador, tarifa y periodo. Siempre dentro de la transacción del llamador.
 */
@Service
public class BillingService implements EnrollmentBilling {

    static final String ACTION_CHARGED = "ENROLLMENT_CHARGE_CREATED";
    static final String ACTION_MONTHLY = "MONTHLY_CHARGES_GENERATED";

    private final PaymentStore store;
    private final AuditWriter audit;

    public BillingService(PaymentStore store, AuditWriter audit) {
        this.store = store;
        this.audit = audit;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID chargeEnrollment(UUID playerId, UUID feeId, LocalDate dueDate, UUID createdBy) {
        FeeInfo fee = requireFee(feeId, "ENROLLMENT", dueDate == null ? List.of(new FieldError("dueDate", "La fecha de vencimiento es obligatoria.")) : List.of());
        UUID created;
        try {
            created = store.insertCharge(playerId, fee.id(), null, fee.amount(), dueDate, createdBy).orElse(null);
        } catch (ReferenceNotFoundException e) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe.");
        }
        if (created == null) {
            return store.findChargeId(playerId, fee.id(), null).orElseThrow();
        }
        audit.write(AuditRecord.builder(ACTION_CHARGED, "charge")
                .actor(createdBy, "COORDINATOR")
                .entity(created)
                .after(Json.of("playerId", playerId, "feeId", fee.id(), "amount", fee.amount(), "dueDate", dueDate))
                .build());
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnrollmentSettled(UUID playerId) {
        return store.enrollmentSettled(playerId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int chargeMonthly(Collection<UUID> playerIds, UUID feeId, LocalDate period, LocalDate dueDate, UUID createdBy) {
        FeeInfo fee = requireFee(feeId, "MONTHLY", PaymentRules.monthlyViolations(period, dueDate));
        int created = 0;
        for (UUID playerId : playerIds) {
            if (store.insertCharge(playerId, fee.id(), period, fee.amount(), dueDate, createdBy).isPresent()) {
                created++;
            }
        }
        audit.write(AuditRecord.builder(ACTION_MONTHLY, "fee")
                .actor(createdBy, "COORDINATOR")
                .entity(fee.id())
                .after(Json.of("period", period, "players", playerIds.size(), "created", created))
                .build());
        return created;
    }

    /** La tarifa debe existir, estar activa y ser del concepto esperado; los demás errores se acumulan con ella. */
    private FeeInfo requireFee(UUID feeId, String concept, List<FieldError> others) {
        List<FieldError> errors = new java.util.ArrayList<>(others);
        FeeInfo fee = feeId == null ? null : store.findFee(feeId).orElse(null);
        if (fee == null) {
            errors.add(new FieldError("feeId", "La tarifa no existe."));
        } else if (!fee.active()) {
            errors.add(new FieldError("feeId", "La tarifa no está activa."));
        } else if (!fee.concept().equals(concept)) {
            errors.add(new FieldError("feeId", "La tarifa debe ser de concepto " + concept + "."));
        }
        ValidationFailure.throwIfAny(errors);
        return fee;
    }
}
