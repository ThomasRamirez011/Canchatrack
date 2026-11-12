package co.edu.unipamplona.canchatrack.payments.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.payments.domain.FieldError;
import co.edu.unipamplona.canchatrack.payments.domain.PaymentRules;

/** Tarifas por concepto y temporada. El club aún no definió montos ni calendario (DP-05): las carga el coordinador. */
@Service
public class FeeService {

    static final String ACTION_CREATED = "FEE_CREATED";

    private final PaymentStore store;
    private final AuditWriter audit;

    public FeeService(PaymentStore store, AuditWriter audit) {
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public FeeInfo create(PaymentsActor actor, CreateFeeCommand command) {
        List<FieldError> errors = new ArrayList<>(PaymentRules.feeViolations(
                command.concept(), command.name(), command.amount(), command.validFrom(), command.validTo()));
        if (command.seasonId() == null) {
            errors.add(new FieldError("seasonId", "La temporada es obligatoria."));
        }
        ValidationFailure.throwIfAny(errors);
        FeeInfo fee;
        try {
            fee = store.insertFee(command.seasonId(), command.concept().trim().toUpperCase(), command.name().trim(),
                    command.amount(), command.validFrom(), command.validTo(), actor.id());
        } catch (ReferenceNotFoundException e) {
            throw ValidationFailure.of("seasonId", "La temporada no existe.");
        }
        audit.write(AuditRecord.builder(ACTION_CREATED, "fee")
                .actor(actor.id(), PaymentsActor.ROLE)
                .entity(fee.id())
                .after(Json.of("seasonId", fee.seasonId(), "concept", fee.concept(), "name", fee.name(), "amount", fee.amount()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return fee;
    }

    @Transactional(readOnly = true)
    public List<FeeInfo> list(UUID seasonId) {
        return store.findFees(seasonId);
    }
}
