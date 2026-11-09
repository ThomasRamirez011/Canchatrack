package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code paidAt} nulo significa "ahora" (reloj de la base de datos). */
public record RecordPaymentCommand(UUID chargeId, BigDecimal amount, String method, String reference, Instant paidAt,
                                   UUID receiptFileId) {
}
