package co.edu.unipamplona.canchatrack.reports.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.MoneyRow;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.NextMatchRow;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.PlayerRow;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.SessionRow;
import co.edu.unipamplona.canchatrack.reports.domain.DashboardRules;

/** Arma el tablero del club a partir de hechos leídos en una sola transacción de solo lectura. */
@Service
public class DashboardService {

    /** Cuántas sesiones recientes se muestran por equipo y qué tan atrás se miran. */
    static final int RECENT_SESSIONS = 5;
    static final int LOOKBACK_DAYS = 120;
    /** Agenda visible: un mes hacia atrás y cuatro hacia adelante. */
    static final int EVENTS_BACK_DAYS = 31;
    static final int EVENTS_AHEAD_DAYS = 120;

    private final DashboardStore store;
    private final Clock clock;
    private final ReportSettings settings;

    public DashboardService(DashboardStore store, Clock clock, ReportSettings settings) {
        this.store = store;
        this.clock = clock;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ClubOverview overview() {
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(settings.zone());
        LocalDate today = local.toLocalDate();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate nextMonthStart = monthStart.plusMonths(1);

        List<PlayerRow> playerRows = store.players(today);
        List<SessionRow> sessions = store.closedSessions(now.minusSeconds(LOOKBACK_DAYS * 86_400L));
        Map<UUID, MoneyRow> money = new HashMap<>();
        store.money(monthStart.atStartOfDay(settings.zone()).toInstant(),
                nextMonthStart.atStartOfDay(settings.zone()).toInstant(), monthStart, nextMonthStart.minusDays(1))
                .forEach(row -> money.put(row.categoryId(), row));
        Map<UUID, NextMatchRow> matches = new HashMap<>();
        store.nextMatches(now).forEach(row -> matches.put(row.categoryId(), row));

        List<ClubOverview.Team> teams = new ArrayList<>();
        for (DashboardStore.TeamBase base : store.teams()) {
            List<PlayerRow> roster = playerRows.stream().filter(p -> p.categoryId().equals(base.categoryId())).toList();
            List<SessionRow> own = sessions.stream().filter(s -> s.categoryId().equals(base.categoryId())).toList();
            long attended = own.stream().mapToLong(SessionRow::attended).sum();
            long counted = own.stream().mapToLong(SessionRow::counted).sum();
            List<ClubOverview.SessionAttendance> recent = own.stream()
                    .skip(Math.max(0, own.size() - RECENT_SESSIONS))
                    .map(s -> new ClubOverview.SessionAttendance(s.eventId(), s.startAt(),
                            DashboardRules.percent(s.attended(), s.counted())))
                    .toList();
            MoneyRow m = money.get(base.categoryId());
            NextMatchRow next = matches.get(base.categoryId());
            teams.add(new ClubOverview.Team(base.categoryId(), base.name(), base.headCoachId(), base.headCoach(), roster.size(),
                    (int) roster.stream().filter(p -> DashboardRules.enabled(p.status(), p.overdue())).count(),
                    DashboardRules.percent(attended, counted), recent,
                    m == null ? BigDecimal.ZERO : m.revenue(), m == null ? BigDecimal.ZERO : m.debt(),
                    m == null ? 0 : DashboardRules.percent(m.periodApplied(), m.periodCharged()),
                    next == null ? null : new ClubOverview.NextMatch(next.eventId(), next.startAt(), next.opponent(), next.venue())));
        }

        List<ClubOverview.Player> players = playerRows.stream()
                .map(p -> new ClubOverview.Player(p.playerId(), p.categoryId(), p.code(), p.fullName(), p.status(),
                        DashboardRules.percent(p.attended(), p.counted()),
                        DashboardRules.enabled(p.status(), p.overdue()), p.pendingCharge()))
                .toList();

        List<ClubOverview.Event> events = store.events(now.minusSeconds(EVENTS_BACK_DAYS * 86_400L),
                now.plusSeconds(EVENTS_AHEAD_DAYS * 86_400L));
        return new ClubOverview(now, teams, players, events);
    }
}
