package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Reporte de cartera (RF-042). {@code categories} siempre suma todos los cargos aunque {@code lines} esté truncada. */
public record ReceivablesReport(LocalDate asOf, Instant generatedAt, List<ReceivableLine> lines,
                                List<CategoryReceivable> categories, boolean truncated) {
}
