package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.reports.application.ClubOverview;
import co.edu.unipamplona.canchatrack.reports.application.DashboardService;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.MoneyRow;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.NextMatchRow;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.PlayerRow;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.SessionRow;
import co.edu.unipamplona.canchatrack.reports.application.DashboardStore.TeamBase;
import co.edu.unipamplona.canchatrack.reports.application.ReportSettings;

class DashboardServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final Instant NOW = Instant.parse("2030-03-15T15:00:00Z");

    private final UUID young = UUID.randomUUID();
    private final UUID older = UUID.randomUUID();
    private final UUID coachId = UUID.randomUUID();

    /** Almacén en memoria que además recuerda con qué fechas lo consultó el servicio. */
    private static final class FakeStore implements DashboardStore {
        List<TeamBase> teams = new ArrayList<>();
        List<PlayerRow> players = new ArrayList<>();
        List<SessionRow> sessions = new ArrayList<>();
        List<MoneyRow> money = new ArrayList<>();
        List<NextMatchRow> matches = new ArrayList<>();
        LocalDate todayAsked;
        Instant moneyFrom;
        Instant moneyTo;
        LocalDate moneyFromDate;
        LocalDate moneyToDate;

        @Override
        public List<TeamBase> teams() {
            return teams;
        }

        @Override
        public List<PlayerRow> players(LocalDate today) {
            todayAsked = today;
            return players;
        }

        @Override
        public List<SessionRow> closedSessions(Instant since) {
            return sessions;
        }

        @Override
        public List<MoneyRow> money(Instant from, Instant to, LocalDate fromDate, LocalDate toDate) {
            moneyFrom = from;
            moneyTo = to;
            moneyFromDate = fromDate;
            moneyToDate = toDate;
            return money;
        }

        @Override
        public List<NextMatchRow> nextMatches(Instant now) {
            return matches;
        }

        @Override
        public List<ClubOverview.Event> events(Instant from, Instant to) {
            return List.of();
        }
    }

    private DashboardService service(FakeStore store) {
        return new DashboardService(store, Clock.fixed(NOW, ZoneOffset.UTC), new ReportSettings(BOGOTA));
    }

    private PlayerRow player(UUID category, String status, long attended, long counted, boolean overdue) {
        return new PlayerRow(UUID.randomUUID(), category, "J-" + UUID.randomUUID(), "Jugador", status, attended, counted,
                overdue, null);
    }

    private SessionRow session(UUID category, int day, long attended, long counted) {
        return new SessionRow(category, UUID.randomUUID(), Instant.parse("2030-03-01T20:00:00Z").plusSeconds(day * 86_400L),
                attended, counted);
    }

    @Test
    void buildsTeamFiguresFromTheFacts() {
        FakeStore store = new FakeStore();
        store.teams.add(new TeamBase(young, "Sub-15", coachId, "Carlos Ramírez"));
        store.teams.add(new TeamBase(older, "Sub-13", null, null));
        store.players.add(player(young, "ACTIVE", 4, 5, false));
        store.players.add(player(young, "ACTIVE", 1, 5, true));       // debe y está vencido: no habilitado
        store.players.add(player(young, "IN_EVALUATION", 0, 0, false)); // en evaluación: no habilitado
        store.sessions.add(session(young, 1, 3, 4));
        store.sessions.add(session(young, 2, 1, 4));
        store.money.add(new MoneyRow(young, new BigDecimal("140000"), new BigDecimal("60000"), new BigDecimal("280000"),
                new BigDecimal("140000")));
        UUID matchEvent = UUID.randomUUID();
        store.matches.add(new NextMatchRow(young, matchEvent, NOW.plusSeconds(3600), "Rivales FC", "Cancha Norte"));

        ClubOverview overview = service(store).overview();

        assertThat(overview.generatedAt()).isEqualTo(NOW);
        assertThat(overview.teams()).hasSize(2);
        ClubOverview.Team team = overview.teams().get(0);
        assertThat(team.name()).isEqualTo("Sub-15");
        assertThat(team.headCoach()).isEqualTo("Carlos Ramírez");
        assertThat(team.headCoachId()).isEqualTo(coachId);
        assertThat(team.rosterSize()).isEqualTo(3);
        assertThat(team.enabledCount()).isEqualTo(1);
        assertThat(team.attendanceAverage()).isEqualTo(50); // 4 de 8
        assertThat(team.recentAttendance()).extracting(ClubOverview.SessionAttendance::percentage).containsExactly(75, 25);
        assertThat(team.monthRevenue()).isEqualByComparingTo("140000");
        assertThat(team.debt()).isEqualByComparingTo("60000");
        assertThat(team.collectionRate()).isEqualTo(50);
        assertThat(team.nextMatch().opponent()).isEqualTo("Rivales FC");
        assertThat(team.nextMatch().eventId()).isEqualTo(matchEvent);
    }

    @Test
    void aTeamWithNoActivityShowsZerosAndNoMatch() {
        FakeStore store = new FakeStore();
        store.teams.add(new TeamBase(older, "Sub-13", null, null));

        ClubOverview.Team team = service(store).overview().teams().get(0);

        assertThat(team.rosterSize()).isZero();
        assertThat(team.enabledCount()).isZero();
        assertThat(team.attendanceAverage()).isZero();
        assertThat(team.recentAttendance()).isEmpty();
        assertThat(team.monthRevenue()).isEqualByComparingTo("0");
        assertThat(team.debt()).isEqualByComparingTo("0");
        assertThat(team.collectionRate()).isZero();
        assertThat(team.nextMatch()).isNull();
        assertThat(team.headCoach()).isNull();
        assertThat(team.headCoachId()).isNull();
    }

    @Test
    void keepsOnlyTheLastFiveSessionsInChronologicalOrder() {
        FakeStore store = new FakeStore();
        store.teams.add(new TeamBase(young, "Sub-15", null, null));
        for (int day = 1; day <= 7; day++) {
            store.sessions.add(session(young, day, day, 10)); // 10 %, 20 %, ... 70 %
        }

        ClubOverview.Team team = service(store).overview().teams().get(0);

        assertThat(team.recentAttendance()).extracting(ClubOverview.SessionAttendance::percentage)
                .containsExactly(30, 40, 50, 60, 70);
        assertThat(team.attendanceAverage()).isEqualTo(40); // 28 de 70 sobre las siete sesiones
    }

    @Test
    void monthAndTodayFollowTheClubZoneNotUtc() {
        FakeStore store = new FakeStore();
        // 1 de abril 02:00 UTC sigue siendo 31 de marzo en Bogotá (UTC-5)
        DashboardService svc = new DashboardService(store, Clock.fixed(Instant.parse("2030-04-01T02:00:00Z"), ZoneOffset.UTC),
                new ReportSettings(BOGOTA));

        svc.overview();

        assertThat(store.todayAsked).isEqualTo(LocalDate.of(2030, 3, 31));
        assertThat(store.moneyFromDate).isEqualTo(LocalDate.of(2030, 3, 1));
        assertThat(store.moneyToDate).isEqualTo(LocalDate.of(2030, 3, 31));
        assertThat(store.moneyFrom).isEqualTo(Instant.parse("2030-03-01T05:00:00Z"));
        assertThat(store.moneyTo).isEqualTo(Instant.parse("2030-04-01T05:00:00Z"));
    }

    @Test
    void playersCarryTheirEnabledFlagAndAttendance() {
        FakeStore store = new FakeStore();
        store.teams.add(new TeamBase(young, "Sub-15", null, null));
        store.players.add(player(young, "ACTIVE", 2, 3, false));
        store.players.add(player(young, "ACTIVE", 0, 0, true));

        List<ClubOverview.Player> players = service(store).overview().players();

        assertThat(players).extracting(ClubOverview.Player::enabled).containsExactly(true, false);
        assertThat(players).extracting(ClubOverview.Player::attendance).containsExactly(67, 0);
    }
}
