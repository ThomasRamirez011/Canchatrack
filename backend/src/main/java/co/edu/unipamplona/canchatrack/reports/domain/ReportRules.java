package co.edu.unipamplona.canchatrack.reports.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Cálculos de los reportes (CU-10 a CU-12). Puros y sin estado. */
public final class ReportRules {

    /** Tope de filas del reporte de cartera; si hay más se marca {@code truncated} y los totales siguen siendo completos. */
    public static final int MAX_RECEIVABLE_LINES = 5000;

    private ReportRules() {
    }

    /** Días transcurridos desde el vencimiento hasta {@code asOf}; cero si aún no vence. */
    public static int daysOverdue(LocalDate dueDate, LocalDate asOf) {
        long days = ChronoUnit.DAYS.between(dueDate, asOf);
        return days <= 0 ? 0 : (int) Math.min(days, Integer.MAX_VALUE);
    }

    /**
     * Porcentaje de asistencia, con la misma fórmula del módulo de asistencia: (presentes + tardes) / (registros − excusas
     * aceptadas), un decimal. {@code null} si no hay nada que contar.
     */
    public static BigDecimal attendancePercentage(int attended, int records, int acceptedExcuses) {
        int counted = records - acceptedExcuses;
        if (counted <= 0) {
            return null;
        }
        return BigDecimal.valueOf(attended * 100L).divide(BigDecimal.valueOf(counted), 1, RoundingMode.HALF_UP);
    }
}
