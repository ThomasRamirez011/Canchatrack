package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Un pago inmutable; {@code netAmount} descuenta ajustes y anulaciones, {@code voided} indica que fue anulado. */
public record PaymentLine(UUID id, UUID playerId, UUID chargeId, BigDecimal amount, BigDecimal netAmount, String method,
                          String reference, Instant paidAt, UUID receiptFileId, boolean voided) {
}
