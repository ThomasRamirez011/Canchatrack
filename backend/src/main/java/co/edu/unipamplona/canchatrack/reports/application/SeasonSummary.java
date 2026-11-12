package co.edu.unipamplona.canchatrack.reports.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resumen de una temporada (base de CU-10 y CU-12): plantilla, agenda, asistencia, partidos y recaudo. Todo se delimita
 * por la ventana de la temporada ({@code startAt} inclusivo, {@code endAt} exclusivo).
 */
public record SeasonSummary(Season season, Instant generatedAt, List<RosterLine> roster, List<EventCount> events,
                            Attendance attendance, Matches matches, Billing billing) {

    public record Season(UUID id, String name, String status, Instant startAt, Instant endAt) {
    }

    /** Jugadores con asignación de categoría vigente en algún momento de la temporada. */
    public record RosterLine(UUID categoryId, String categoryName, int capacity, int players) {
    }

    public record EventCount(String eventType, String status, int total) {
    }

    /** Solo sesiones cerradas de eventos no cancelados; {@code percentage} es nulo si no hay registros que contar. */
    public record Attendance(int sessionsClosed, int present, int late, int excused, int acceptedExcuses, int absent,
                             BigDecimal percentage) {
    }

    /** Partidos completados con resultado registrado. */
    public record Matches(int played, int wins, int draws, int losses, int goalsFor, int goalsAgainst) {
    }

    /** Cargos no anulados de las tarifas de la temporada; {@code overdue} es el saldo ya vencido al día de hoy. */
    public record Billing(BigDecimal charged, BigDecimal applied, BigDecimal outstanding, BigDecimal overdue) {
    }
}
