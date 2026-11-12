package co.edu.unipamplona.canchatrack.reports.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Totales de cartera por categoría. {@code categoryId} es nulo para jugadores sin categoría vigente. */
public record CategoryReceivable(UUID categoryId, String categoryName, int players, BigDecimal charged, BigDecimal applied,
                                 BigDecimal outstanding, BigDecimal overdue) {
}
