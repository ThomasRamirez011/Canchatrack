package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.reports.application.CategoryReceivable;
import co.edu.unipamplona.canchatrack.reports.application.ReceivableLine;
import co.edu.unipamplona.canchatrack.reports.application.ReceivablesReport;
import co.edu.unipamplona.canchatrack.reports.application.SeasonSummary;

/** Contratos JSON de los reportes (ver openapi/canchatrack-v1.yaml). */
final class ReportDtos {

    private ReportDtos() {
    }

    record ReceivableLineResponse(UUID playerId, String playerCode, String playerName, UUID categoryId, String categoryName,
                                  String guardianName, String concept, String feeName, LocalDate period, LocalDate dueDate,
                                  BigDecimal amount, BigDecimal applied, BigDecimal outstanding, int daysOverdue,
                                  String status) {

        static ReceivableLineResponse from(ReceivableLine l) {
            return new ReceivableLineResponse(l.playerId(), l.playerCode(), l.playerName(), l.categoryId(), l.categoryName(),
                    l.guardianName(), l.concept(), l.feeName(), l.period(), l.dueDate(), l.amount(), l.applied(),
                    l.outstanding(), l.daysOverdue(), l.status());
        }
    }

    record CategoryReceivableResponse(UUID categoryId, String categoryName, int players, BigDecimal charged,
                                      BigDecimal applied, BigDecimal outstanding, BigDecimal overdue) {

        static CategoryReceivableResponse from(CategoryReceivable c) {
            return new CategoryReceivableResponse(c.categoryId(), c.categoryName(), c.players(), c.charged(), c.applied(),
                    c.outstanding(), c.overdue());
        }
    }

    record ReceivablesReportResponse(LocalDate asOf, Instant generatedAt, List<ReceivableLineResponse> lines,
                                     List<CategoryReceivableResponse> categories, boolean truncated) {

        static ReceivablesReportResponse from(ReceivablesReport r) {
            return new ReceivablesReportResponse(r.asOf(), r.generatedAt(),
                    r.lines().stream().map(ReceivableLineResponse::from).toList(),
                    r.categories().stream().map(CategoryReceivableResponse::from).toList(), r.truncated());
        }
    }

    record SeasonInfoResponse(UUID id, String name, String status, Instant startAt, Instant endAt) {
    }

    record SeasonRosterLineResponse(UUID categoryId, String categoryName, int capacity, int players) {
    }

    record SeasonEventCountResponse(String eventType, String status, int total) {
    }

    record SeasonAttendanceResponse(int sessionsClosed, int present, int late, int excused, int acceptedExcuses, int absent,
                                    BigDecimal percentage) {
    }

    record SeasonMatchesResponse(int played, int wins, int draws, int losses, int goalsFor, int goalsAgainst) {
    }

    record SeasonBillingResponse(BigDecimal charged, BigDecimal applied, BigDecimal outstanding, BigDecimal overdue) {
    }

    record SeasonSummaryResponse(SeasonInfoResponse season, Instant generatedAt, List<SeasonRosterLineResponse> roster,
                                 List<SeasonEventCountResponse> events, SeasonAttendanceResponse attendance,
                                 SeasonMatchesResponse matches, SeasonBillingResponse billing) {

        static SeasonSummaryResponse from(SeasonSummary s) {
            SeasonSummary.Season season = s.season();
            SeasonSummary.Attendance a = s.attendance();
            SeasonSummary.Matches m = s.matches();
            SeasonSummary.Billing b = s.billing();
            return new SeasonSummaryResponse(
                    new SeasonInfoResponse(season.id(), season.name(), season.status(), season.startAt(), season.endAt()),
                    s.generatedAt(),
                    s.roster().stream().map(r -> new SeasonRosterLineResponse(r.categoryId(), r.categoryName(), r.capacity(),
                            r.players())).toList(),
                    s.events().stream().map(e -> new SeasonEventCountResponse(e.eventType(), e.status(), e.total())).toList(),
                    new SeasonAttendanceResponse(a.sessionsClosed(), a.present(), a.late(), a.excused(), a.acceptedExcuses(),
                            a.absent(), a.percentage()),
                    new SeasonMatchesResponse(m.played(), m.wins(), m.draws(), m.losses(), m.goalsFor(), m.goalsAgainst()),
                    new SeasonBillingResponse(b.charged(), b.applied(), b.outstanding(), b.overdue()));
        }
    }
}
