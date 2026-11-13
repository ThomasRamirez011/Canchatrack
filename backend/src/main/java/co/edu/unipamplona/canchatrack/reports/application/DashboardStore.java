package co.edu.unipamplona.canchatrack.reports.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Puerto de lectura del tablero del club (SQL de solo lectura sobre tablas de otros módulos). */
public interface DashboardStore {

    List<TeamBase> teams();

    /** Jugadores con categoría vigente y estado de trabajo (activos o en evaluación). {@code today} fija qué es «vencido». */
    List<PlayerRow> players(LocalDate today);

    /** Sesiones de asistencia cerradas de eventos no cancelados desde {@code since}, de la más antigua a la más reciente. */
    List<SessionRow> closedSessions(Instant since);

    /** Dinero por categoría: recaudo del periodo, cartera pendiente total y cobro de los cargos que vencen en el periodo. */
    List<MoneyRow> money(Instant from, Instant to, LocalDate fromDate, LocalDate toDate);

    List<NextMatchRow> nextMatches(Instant now);

    List<ClubOverview.Event> events(Instant from, Instant to);

    record TeamBase(UUID categoryId, String name, UUID headCoachId, String headCoach) {
    }

    record PlayerRow(UUID playerId, UUID categoryId, String code, String fullName, String status, long attended,
                     long counted, boolean overdue, ClubOverview.PendingCharge pendingCharge) {
    }

    record SessionRow(UUID categoryId, UUID eventId, Instant startAt, long attended, long counted) {
    }

    record MoneyRow(UUID categoryId, BigDecimal revenue, BigDecimal debt, BigDecimal periodCharged,
                    BigDecimal periodApplied) {
    }

    record NextMatchRow(UUID categoryId, UUID eventId, Instant startAt, String opponent, String venue) {
    }
}
