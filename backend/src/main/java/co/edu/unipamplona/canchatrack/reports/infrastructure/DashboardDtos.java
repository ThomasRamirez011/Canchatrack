package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.reports.application.ClubOverview;

/** Contrato JSON del tablero del club (ver openapi/canchatrack-v1.yaml). */
final class DashboardDtos {

    private DashboardDtos() {
    }

    record SessionAttendanceResponse(UUID eventId, Instant startAt, int percentage) {
    }

    record NextMatchResponse(UUID eventId, Instant startAt, String opponent, String venue) {
    }

    record TeamResponse(UUID categoryId, String name, UUID headCoachId, String headCoach, int rosterSize, int enabledCount,
                        int attendanceAverage, List<SessionAttendanceResponse> recentAttendance, BigDecimal monthRevenue,
                        BigDecimal debt, int collectionRate, NextMatchResponse nextMatch) {
    }

    record PendingChargeResponse(UUID chargeId, String concept, String feeName, LocalDate period, LocalDate dueDate,
                                 BigDecimal outstanding) {
    }

    record DashboardPlayerResponse(UUID playerId, UUID categoryId, String code, String fullName, String status,
                                   int attendance, boolean enabled, PendingChargeResponse pendingCharge) {
    }

    record DashboardEventResponse(UUID id, UUID categoryId, String eventType, String status, Instant startAt,
                                  Instant endAt, String venue, String opponent) {
    }

    record ClubOverviewResponse(Instant generatedAt, List<TeamResponse> teams, List<DashboardPlayerResponse> players,
                                List<DashboardEventResponse> events) {

        static ClubOverviewResponse from(ClubOverview o) {
            return new ClubOverviewResponse(o.generatedAt(),
                    o.teams().stream().map(t -> new TeamResponse(t.categoryId(), t.name(), t.headCoachId(), t.headCoach(), t.rosterSize(),
                            t.enabledCount(), t.attendanceAverage(),
                            t.recentAttendance().stream()
                                    .map(s -> new SessionAttendanceResponse(s.eventId(), s.startAt(), s.percentage())).toList(),
                            t.monthRevenue(), t.debt(), t.collectionRate(),
                            t.nextMatch() == null ? null : new NextMatchResponse(t.nextMatch().eventId(),
                                    t.nextMatch().startAt(), t.nextMatch().opponent(), t.nextMatch().venue()))).toList(),
                    o.players().stream().map(p -> new DashboardPlayerResponse(p.playerId(), p.categoryId(), p.code(),
                            p.fullName(), p.status(), p.attendance(), p.enabled(),
                            p.pendingCharge() == null ? null : new PendingChargeResponse(p.pendingCharge().chargeId(),
                                    p.pendingCharge().concept(), p.pendingCharge().feeName(), p.pendingCharge().period(),
                                    p.pendingCharge().dueDate(), p.pendingCharge().outstanding()))).toList(),
                    o.events().stream().map(e -> new DashboardEventResponse(e.id(), e.categoryId(), e.eventType(),
                            e.status(), e.startAt(), e.endAt(), e.venue(), e.opponent())).toList());
        }
    }
}
