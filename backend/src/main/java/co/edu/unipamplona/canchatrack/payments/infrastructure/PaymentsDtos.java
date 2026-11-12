package co.edu.unipamplona.canchatrack.payments.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.payments.application.AccountView;
import co.edu.unipamplona.canchatrack.payments.application.ChargeLine;
import co.edu.unipamplona.canchatrack.payments.application.FeeInfo;
import co.edu.unipamplona.canchatrack.payments.application.PaymentLine;

/**
 * Contratos JSON de tarifas, pagos y estado de cuenta (ver openapi/canchatrack-v1.yaml). Las solicitudes no llevan
 * anotaciones de Bean Validation: las reglas viven en el dominio para devolver todos los errores juntos.
 */
final class PaymentsDtos {

    private PaymentsDtos() {
    }

    record CreateFeeRequest(UUID seasonId, String concept, String name, BigDecimal amount, LocalDate validFrom,
                            LocalDate validTo) {
    }

    record RecordPaymentRequest(UUID chargeId, BigDecimal amount, String method, String reference, Instant paidAt,
                                UUID receiptFileId) {
    }

    record VoidPaymentRequest(String reason) {
    }

    record FeeResponse(UUID id, UUID seasonId, String concept, String name, BigDecimal amount, String currency,
                       LocalDate validFrom, LocalDate validTo, boolean active) {

        static FeeResponse from(FeeInfo fee) {
            return new FeeResponse(fee.id(), fee.seasonId(), fee.concept(), fee.name(), fee.amount(), fee.currency(),
                    fee.validFrom(), fee.validTo(), fee.active());
        }
    }

    record ChargeResponse(UUID id, UUID feeId, String feeName, String concept, LocalDate period, LocalDate dueDate,
                          BigDecimal amount, BigDecimal adjusted, BigDecimal netAmount, BigDecimal applied,
                          BigDecimal outstanding, String status) {

        static ChargeResponse from(ChargeLine c) {
            return new ChargeResponse(c.chargeId(), c.feeId(), c.feeName(), c.concept(), c.period(), c.dueDate(),
                    c.amount(), c.adjusted(), c.netAmount(), c.applied(), c.outstanding(), c.status());
        }
    }

    record PaymentResponse(UUID id, UUID playerId, UUID chargeId, BigDecimal amount, BigDecimal netAmount,
                           String method, String reference, Instant paidAt, UUID receiptFileId, boolean voided) {

        static PaymentResponse from(PaymentLine p) {
            return new PaymentResponse(p.id(), p.playerId(), p.chargeId(), p.amount(), p.netAmount(), p.method(),
                    p.reference(), p.paidAt(), p.receiptFileId(), p.voided());
        }
    }

    record AccountResponse(UUID playerId, BigDecimal charged, BigDecimal paid, BigDecimal balance,
                           List<ChargeResponse> charges, List<PaymentResponse> payments) {

        static AccountResponse from(AccountView a) {
            return new AccountResponse(a.playerId(), a.charged(), a.paid(), a.balance(),
                    a.charges().stream().map(ChargeResponse::from).toList(),
                    a.payments().stream().map(PaymentResponse::from).toList());
        }
    }
}
