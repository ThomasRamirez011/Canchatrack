package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de lectura de las exportaciones del ERS 7.3. Como {@link ReportStore}, es un modelo de lectura: consulta tablas de
 * otros módulos solo para leer. Cada consulta devuelve las celdas de cada fila en el orden de {@link ExportColumns}, con
 * fechas y horas ya expresadas en la zona del club, a lo sumo {@code limit} filas.
 */
public interface ExportStore {

    /** Periodo de una temporada, o vacío si no existe. */
    Optional<Period> seasonPeriod(UUID seasonId);

    boolean categoryExists(UUID categoryId);

    boolean tournamentExists(UUID tournamentId);

    /** Jugadores con categoría vigente, por categoría y apellido. */
    List<List<Object>> roster(UUID categoryId, int limit);

    /** Una fila por jugador y sesión cerrada (eventos no cancelados) que empieza en {@code [from, to)}. */
    List<List<Object>> attendance(UUID categoryId, Instant from, Instant to, int limit);

    /** Una fila por partido y convocado (sin los retirados); un partido sin convocados sale con las celdas del jugador vacías. */
    List<List<Object>> matches(UUID categoryId, UUID tournamentId, Instant from, Instant to, int limit);

    /** Una fila por ronda de evaluación cuyo periodo toca {@code [firstDay, lastDay]} (ambos inclusivos). */
    List<List<Object>> evaluations(UUID categoryId, LocalDate firstDay, LocalDate lastDay, int limit);

    /** Periodo de una temporada: {@code start} inclusivo y {@code end} exclusivo. */
    record Period(Instant start, Instant end) {
    }
}
