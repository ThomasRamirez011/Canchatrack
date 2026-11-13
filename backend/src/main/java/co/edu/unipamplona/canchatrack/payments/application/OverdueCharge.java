package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Cargo vencido con saldo, con lo necesario para avisar al acudiente (sin datos de salud, RN-17). */
public record OverdueCharge(UUID chargeId, UUID playerId, String playerName, String concept, LocalDate period,
                            LocalDate dueDate, BigDecimal outstanding, String currency) {
}
