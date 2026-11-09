package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Un cargo con su saldo derivado ({@code charge_balances}); {@code status}: PENDING, PARTIAL, SETTLED o VOIDED. */
public record ChargeLine(UUID chargeId, UUID playerId, UUID feeId, String feeName, String concept, LocalDate period,
                         LocalDate dueDate, BigDecimal amount, BigDecimal adjusted, BigDecimal netAmount,
                         BigDecimal applied, BigDecimal outstanding, String status) {
}
