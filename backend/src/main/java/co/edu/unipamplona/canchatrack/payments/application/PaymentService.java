package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.api.StoredFileDirectory;
import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.payments.domain.FieldError;
import co.edu.unipamplona.canchatrack.payments.domain.PaymentRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Pagos inmutables y estado de cuenta (RN-16, PA-12). Un pago nunca se edita ni se borra: un error se corrige anulándolo
 * (asiento compensatorio con motivo) y registrando el correcto. Un pago se aplica siempre a un cargo del jugador y no
 * puede superar lo que ese cargo aún debe; se bloquea el cargo para que dos pagos simultáneos no lo sobrepasen.
 */
@Service
public class PaymentService {

    static final String ACTION_RECORDED = "PAYMENT_RECORDED";
    static final String ACTION_VOIDED = "PAYMENT_VOIDED";
    private static final String ENTITY = "payment";

    private final PaymentStore store;
    private final StoredFileDirectory files;
    private final PlayerAccessPolicy access;
    private final AuditWriter audit;

    public PaymentService(PaymentStore store, StoredFileDirectory files, PlayerAccessPolicy access, AuditWriter audit) {
        this.store = store;
        this.files = files;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public PaymentLine record(PaymentsActor actor, UUID playerId, RecordPaymentCommand command) {
        List<FieldError> errors = new ArrayList<>(PaymentRules.paymentViolations(command.amount(), command.method(), command.reference()));
        if (command.chargeId() == null) {
            errors.add(new FieldError("chargeId", "El cargo al que se aplica el pago es obligatorio."));
        }
        if (command.receiptFileId() != null && !files.exists(command.receiptFileId())) {
            errors.add(new FieldError("receiptFileId", "El archivo del comprobante no existe."));
        }
        Instant now = store.now();
        if (command.paidAt() != null && command.paidAt().isAfter(now)) {
            errors.add(new FieldError("paidAt", "La fecha del pago no puede ser futura."));
        }
        ValidationFailure.throwIfAny(errors);

        store.lockCharge(command.chargeId());
        ChargeLine charge = store.findChargeLine(command.chargeId()).filter(c -> c.playerId().equals(playerId)).orElse(null);
        if (charge == null) {
            throw ValidationFailure.of("chargeId", "El cargo no existe para este jugador.");
        }
        if ("VOIDED".equals(charge.status()) || "SETTLED".equals(charge.status())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "El cargo ya está " + ("VOIDED".equals(charge.status()) ? "anulado." : "saldado."));
        }
        if (command.amount().compareTo(charge.outstanding()) > 0) {
            throw ValidationFailure.of("amount", "El pago excede lo que aún se debe de este cargo ("
                    + charge.outstanding().toPlainString() + ").");
        }
        UUID id;
        try {
            id = store.insertPayment(playerId, charge.chargeId(), command.amount(), command.method().trim().toUpperCase(),
                    blankToNull(command.reference()), command.receiptFileId(), command.paidAt() == null ? now : command.paidAt(),
                    actor.id());
        } catch (ReferenceNotFoundException e) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe.");
        }
        PaymentLine payment = store.findPayment(id).orElseThrow();
        audit.write(AuditRecord.builder(ACTION_RECORDED, ENTITY)
                .actor(actor.id(), PaymentsActor.ROLE)
                .entity(id)
                .after(Json.of("playerId", playerId, "chargeId", charge.chargeId(), "amount", payment.amount(),
                        "method", payment.method()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return payment;
    }

    @Transactional
    public PaymentLine voidPayment(PaymentsActor actor, UUID paymentId, String reason) {
        ValidationFailure.throwIfAny(PaymentRules.voidViolations(reason));
        PaymentLine before = store.findPayment(paymentId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El pago no existe."));
        if (!store.voidPayment(paymentId, reason.trim(), actor.id())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "El pago ya está anulado.");
        }
        PaymentLine after = store.findPayment(paymentId).orElseThrow();
        audit.write(AuditRecord.builder(ACTION_VOIDED, ENTITY)
                .actor(actor.id(), PaymentsActor.ROLE)
                .entity(paymentId)
                .before(Json.of("amount", before.netAmount(), "voided", false))
                .after(Json.of("amount", after.netAmount(), "voided", true))
                .reason(reason.trim())
                .origin(actor.ip(), actor.userAgent())
                .build());
        return after;
    }

    @Transactional(readOnly = true)
    public AccountView account(UUID userId, Set<String> roles, UUID playerId) {
        // Sin acceso y sin existencia responden igual (404): no se revela qué jugadores existen (PA-04).
        if (!access.canAccess(userId, roles, playerId)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe.");
        }
        BigDecimal[] totals = store.totalsOf(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        return new AccountView(playerId, totals[0], totals[1], totals[2], store.chargesOf(playerId), store.paymentsOf(playerId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
