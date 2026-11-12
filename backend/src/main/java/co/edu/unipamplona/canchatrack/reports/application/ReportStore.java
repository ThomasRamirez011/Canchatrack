package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de lectura de los reportes. Es un modelo de lectura: la implementación consulta tablas y vistas de otros módulos
 * solo para leer, sin depender de sus clases (el módulo no escribe nada).
 */
public interface ReportStore {

    /** Cargos con saldo pendiente al {@code asOf}, ordenados por categoría y vencimiento; a lo sumo {@code limit} filas. */
    List<ReceivableLine> receivables(UUID seasonId, UUID categoryId, LocalDate asOf, int limit);

    /** Totales por categoría de los mismos cargos que {@link #receivables}, sin tope de filas. */
    List<CategoryReceivable> receivableTotals(UUID seasonId, UUID categoryId, LocalDate asOf);

    /** Resumen de la temporada o vacío si no existe. {@code today} fija qué saldo está vencido. */
    Optional<SeasonSummary> seasonSummary(UUID seasonId, LocalDate today, Instant generatedAt);

    boolean categoryExists(UUID categoryId);

    boolean seasonExists(UUID seasonId);
}
