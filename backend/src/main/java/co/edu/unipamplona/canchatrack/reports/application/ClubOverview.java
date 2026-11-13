package co.edu.unipamplona.canchatrack.reports.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tablero del club: una sola lectura con lo que necesita la pantalla principal (equipos, jugadores y agenda). Es un modelo
 * de lectura: no sustituye a los endpoints de cada módulo, que siguen siendo la fuente para operar.
 */
public record ClubOverview(Instant generatedAt, List<Team> teams, List<Player> players, List<Event> events) {

    public record Team(UUID categoryId, String name, UUID headCoachId, String headCoach, int rosterSize, int enabledCount,
                       int attendanceAverage, List<SessionAttendance> recentAttendance, BigDecimal monthRevenue,
                       BigDecimal debt, int collectionRate, NextMatch nextMatch) {
    }

    public record SessionAttendance(UUID eventId, Instant startAt, int percentage) {
    }

    public record NextMatch(UUID eventId, Instant startAt, String opponent, String venue) {
    }

    public record Player(UUID playerId, UUID categoryId, String code, String fullName, String status, int attendance,
                         boolean enabled, PendingCharge pendingCharge) {
    }

    public record PendingCharge(UUID chargeId, String concept, String feeName, LocalDate period, LocalDate dueDate,
                                BigDecimal outstanding) {
    }

    public record Event(UUID id, UUID categoryId, String eventType, String status, Instant startAt, Instant endAt,
                        String venue, String opponent) {
    }
}
