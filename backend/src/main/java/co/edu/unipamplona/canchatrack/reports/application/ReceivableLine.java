package co.edu.unipamplona.canchatrack.reports.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un cargo con saldo pendiente (ERS 7.3 «Cartera»): jugador, acudiente principal, concepto, valor, pagos aplicados, saldo
 * y días de mora. {@code amount} es el valor neto del cargo (con descuentos y ajustes).
 */
public record ReceivableLine(UUID playerId, String playerCode, String playerName, UUID categoryId, String categoryName,
                             String guardianName, String concept, String feeName, LocalDate period, LocalDate dueDate,
                             BigDecimal amount, BigDecimal applied, BigDecimal outstanding, int daysOverdue, String status) {
}
