package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Estado de cuenta de un jugador: totales derivados, cargos y pagos (DAD 7.2). */
public record AccountView(UUID playerId, BigDecimal charged, BigDecimal paid, BigDecimal balance,
                          List<ChargeLine> charges, List<PaymentLine> payments) {
}
