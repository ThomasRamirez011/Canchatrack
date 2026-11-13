package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.reports.application.ExportColumns;
import co.edu.unipamplona.canchatrack.reports.application.ExportFile;
import co.edu.unipamplona.canchatrack.reports.application.ExportService;
import co.edu.unipamplona.canchatrack.reports.application.ExportStore;
import co.edu.unipamplona.canchatrack.reports.application.ReportSettings;
import co.edu.unipamplona.canchatrack.reports.application.ReportsActor;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class ExportServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    // 2030-03-10 15:00 UTC = 10:00 en Bogotá
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-03-10T15:00:00Z"), ZoneId.of("UTC"));

    private final FakeStore store = new FakeStore();
    private final Recorder audit = new Recorder();
    private final ExportService service = new ExportService(store, audit, CLOCK, new ReportSettings(BOGOTA));
    private final ReportsActor actor = new ReportsActor(UUID.randomUUID(), "COORDINATOR", null, "test");

    private static List<Map<String, String>> errorsOf(DomainException e) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> errors = (List<Map<String, String>>) e.getExtensions().get("errors");
        return errors;
    }

    private static List<String> fieldsOf(DomainException e) {
        return errorsOf(e).stream().map(err -> err.get("field")).toList();
    }

    private DomainException failure(Runnable action) {
        try {
            action.run();
        } catch (DomainException e) {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            return e;
        }
        throw new AssertionError("Se esperaba un 422");
    }

    private static List<String> lines(ExportFile file) {
        return Arrays.asList(file.csv().split("\r\n"));
    }

    // ---------- plantilla ----------

    @Test
    void rosterListsTheCurrentSquadWithItsHeaderAndAuditsTheDownload() {
        store.roster = List.of(
                row("Sub-12", "JG-000001", "Ana Ruiz", LocalDate.of(2018, 3, 1), "ACTIVE", "Coach Uno", "Madre Ruiz", LocalDate.of(2030, 1, 5)),
                row("Sub-12", "JG-000002", "Luis Paz", LocalDate.of(2018, 4, 2), "ACTIVE", "Coach Uno", "", LocalDate.of(2030, 1, 6)));

        ExportFile file = service.roster(actor, null);

        assertThat(file.fileName()).isEqualTo("plantilla-2030-03-10.csv");
        assertThat(file.rows()).isEqualTo(2);
        assertThat(lines(file)).hasSize(3);
        assertThat(lines(file).get(0)).isEqualTo(String.join(",", ExportColumns.ROSTER));
        assertThat(lines(file).get(1)).isEqualTo("Sub-12,JG-000001,Ana Ruiz,2018-03-01,ACTIVE,Coach Uno,Madre Ruiz,2030-01-05");
        assertThat(audit.records).hasSize(1);
        AuditRecord recorded = audit.records.get(0);
        assertThat(recorded.action()).isEqualTo("REPORT_EXPORTED");
        assertThat(recorded.actorRole()).isEqualTo("COORDINATOR");
        assertThat(recorded.afterJson()).contains("\"report\":\"roster\"").contains("\"format\":\"csv\"").contains("\"rows\":2");
    }

    @Test
    void rosterRejectsAnUnknownCategoryWithoutQueryingOrAuditing() {
        DomainException e = failure(() -> service.roster(actor, UUID.randomUUID()));
        assertThat(errorsOf(e)).singleElement().satisfies(err -> {
            assertThat(err.get("field")).isEqualTo("categoryId");
            assertThat(err.get("message")).isEqualTo("La categoría no existe.");
        });
        assertThat(store.queries).isZero();
        assertThat(audit.records).isEmpty();
    }

    @Test
    void rosterFiltersByAKnownCategory() {
        UUID category = UUID.randomUUID();
        store.categories.add(category);
        service.roster(actor, category);
        assertThat(store.lastCategory).isEqualTo(category);
        assertThat(audit.records.get(0).afterJson()).contains(category.toString());
    }

    // ---------- asistencia ----------

    @Test
    void attendanceNeedsDatesOrASeason() {
        DomainException e = failure(() -> service.attendance(actor, null, null, null, null));
        assertThat(fieldsOf(e)).containsExactly("from", "to");
        assertThat(store.queries).isZero();
        assertThat(audit.records).isEmpty();
    }

    @Test
    void attendanceUsesLocalMidnightsAndNamesTheFileAfterTheRange() {
        store.attendance = List.of(row(LocalDate.of(2030, 2, 4), LocalTime.of(17, 0), "TRAINING", "Sub-12", "Cancha 1",
                "JG-000001", "Ana Ruiz", "PRESENT", "No"));

        ExportFile file = service.attendance(actor, null, null, LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 28));

        assertThat(store.from).isEqualTo(Instant.parse("2030-02-01T05:00:00Z"));
        assertThat(store.to).isEqualTo(Instant.parse("2030-03-01T05:00:00Z"));
        assertThat(file.fileName()).isEqualTo("asistencia-2030-02-01_2030-02-28.csv");
        assertThat(lines(file).get(0)).isEqualTo(String.join(",", ExportColumns.ATTENDANCE));
        assertThat(lines(file).get(1)).isEqualTo("2030-02-04,17:00,TRAINING,Sub-12,Cancha 1,JG-000001,Ana Ruiz,PRESENT,No");
        assertThat(audit.records.get(0).afterJson()).contains("\"report\":\"attendance\"")
                .contains("\"from\":\"2030-02-01\"").contains("\"to\":\"2030-02-28\"");
    }

    @Test
    void aSeasonSetsTheWindowAndDatesNarrowIt() {
        UUID season = UUID.randomUUID();
        store.seasons.put(season, new ExportStore.Period(Instant.parse("2030-01-01T05:00:00Z"), Instant.parse("2031-01-01T05:00:00Z")));

        service.attendance(actor, season, null, null, null);
        assertThat(store.from).isEqualTo(Instant.parse("2030-01-01T05:00:00Z"));
        assertThat(store.to).isEqualTo(Instant.parse("2031-01-01T05:00:00Z"));

        service.attendance(actor, season, null, LocalDate.of(2030, 6, 1), null);
        assertThat(store.from).isEqualTo(Instant.parse("2030-06-01T05:00:00Z"));
        assertThat(store.to).isEqualTo(Instant.parse("2031-01-01T05:00:00Z"));
    }

    @Test
    void anUnknownSeasonIsReportedTogetherWithOtherErrors() {
        DomainException e = failure(() -> service.attendance(actor, UUID.randomUUID(), UUID.randomUUID(), null, null));
        assertThat(fieldsOf(e)).containsExactlyInAnyOrder("seasonId", "categoryId");
        assertThat(audit.records).isEmpty();
    }

    @Test
    void oversizedResultsAreRejectedInsteadOfTruncated() {
        store.attendance = new ArrayList<>();
        for (int i = 0; i < 20_001; i++) {
            store.attendance.add(row("x"));
        }
        DomainException e = failure(() -> service.attendance(actor, null, null, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31)));
        assertThat(errorsOf(e)).singleElement().satisfies(err -> assertThat(err.get("message")).contains("20000"));
        assertThat(store.lastLimit).isEqualTo(20_001);
        assertThat(audit.records).isEmpty();
    }

    @Test
    void exactlyTheCapIsAccepted() {
        store.attendance = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            store.attendance.add(row("x"));
        }
        ExportFile file = service.attendance(actor, null, null, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31));
        assertThat(file.rows()).isEqualTo(20_000);
    }

    // ---------- convocatorias y resultados ----------

    @Test
    void matchesRejectAnUnknownTournamentAndCategoryTogether() {
        DomainException e = failure(() -> service.matches(actor, null, UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31)));
        assertThat(fieldsOf(e)).containsExactlyInAnyOrder("categoryId", "tournamentId");
    }

    @Test
    void matchesKeepEmptyCellsForAnUncalledMatchAndCountEveryRow() {
        UUID tournament = UUID.randomUUID();
        store.tournaments.add(tournament);
        store.matches = List.of(
                row(LocalDate.of(2030, 2, 9), LocalTime.of(9, 30), "Sub-12", "TOURNAMENT_MATCH", "Copa", "Rival FC", "Estadio",
                        "COMPLETED", 3, 1, "JG-000001", "Ana Ruiz", "CONFIRMED", 60, 2),
                row(LocalDate.of(2030, 2, 16), LocalTime.of(9, 30), "Sub-12", "FRIENDLY_MATCH", "", "Otro", "Estadio",
                        "CONFIRMED", null, null, "", "", "", null, null));

        ExportFile file = service.matches(actor, null, null, tournament, LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 28));

        assertThat(store.lastTournament).isEqualTo(tournament);
        assertThat(file.fileName()).isEqualTo("convocatorias-2030-02-01_2030-02-28.csv");
        assertThat(lines(file).get(0)).isEqualTo(String.join(",", ExportColumns.MATCHES));
        assertThat(lines(file).get(1)).isEqualTo("2030-02-09,09:30,Sub-12,TOURNAMENT_MATCH,Copa,Rival FC,Estadio,COMPLETED,3,1,JG-000001,Ana Ruiz,CONFIRMED,60,2");
        assertThat(lines(file).get(2)).isEqualTo("2030-02-16,09:30,Sub-12,FRIENDLY_MATCH,,Otro,Estadio,CONFIRMED,,,,,,,");
        assertThat(audit.records.get(0).afterJson()).contains("\"report\":\"matches\"").contains(tournament.toString());
    }

    // ---------- seguimiento de evaluación ----------

    @Test
    void evaluationsReceiveTheInclusiveDaysOfTheWindow() {
        store.evaluations = List.of(row("Sub-12", "JG-000001", "Ana Ruiz", LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 28),
                LocalDate.of(2030, 3, 5), "CLOSED", "APPROVED", LocalDate.of(2030, 3, 4), 3, 3, 0, 0));

        ExportFile file = service.evaluations(actor, null, null, LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 28));

        assertThat(store.firstDay).isEqualTo(LocalDate.of(2030, 2, 1));
        assertThat(store.lastDay).isEqualTo(LocalDate.of(2030, 2, 28));
        assertThat(file.fileName()).isEqualTo("evaluaciones-2030-02-01_2030-02-28.csv");
        assertThat(lines(file).get(0)).isEqualTo(String.join(",", ExportColumns.EVALUATIONS));
        assertThat(lines(file).get(1)).isEqualTo("Sub-12,JG-000001,Ana Ruiz,2030-02-01,2030-02-28,2030-03-05,CLOSED,APPROVED,2030-03-04,3,3,0,0");
        assertThat(audit.records.get(0).afterJson()).contains("\"report\":\"evaluations\"");
    }

    @Test
    void evaluationHeadersDoNotRevealWhoGaveEachConcept() {
        assertThat(ExportColumns.EVALUATIONS).noneMatch(h -> h.toLowerCase().contains("evaluador")
                || h.toLowerCase().contains("justificación"));
    }

    // ---------- seguridad del CSV ----------

    @Test
    void textsThatLookLikeFormulasAreNeutralisedAndCommasAreQuoted() {
        store.roster = List.of(row("Sub-12", "JG-000001", "=HYPERLINK(\"http://x\")", LocalDate.of(2018, 3, 1), "ACTIVE",
                "Coach, Uno", "+57 300", LocalDate.of(2030, 1, 5)));

        ExportFile file = service.roster(actor, null);

        assertThat(lines(file).get(1)).isEqualTo("Sub-12,JG-000001,\"'=HYPERLINK(\"\"http://x\"\")\",2018-03-01,ACTIVE,\"Coach, Uno\",'+57 300,2030-01-05");
    }

    @Test
    void noExportHasColumnsForIdentityDocumentsContactOrHealthData() {
        List<String> all = new ArrayList<>();
        all.addAll(ExportColumns.ROSTER);
        all.addAll(ExportColumns.ATTENDANCE);
        all.addAll(ExportColumns.MATCHES);
        all.addAll(ExportColumns.EVALUATIONS);
        assertThat(all).noneMatch(h -> {
            String lower = h.toLowerCase();
            return lower.contains("documento") || lower.contains("teléfono") || lower.contains("correo")
                    || lower.contains("salud") || lower.contains("certificado") || lower.contains("causa");
        });
    }

    // ---------- dobles ----------

    private static List<Object> row(Object... cells) {
        return new ArrayList<>(Arrays.asList(cells));
    }

    private static final class Recorder implements AuditWriter {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void write(AuditRecord record) {
            records.add(record);
        }

        @Override
        public void writeIndependent(AuditRecord record) {
            records.add(record);
        }
    }

    private static final class FakeStore implements ExportStore {
        final Map<UUID, Period> seasons = new java.util.HashMap<>();
        final Set<UUID> categories = new HashSet<>();
        final Set<UUID> tournaments = new HashSet<>();
        List<List<Object>> roster = List.of();
        List<List<Object>> attendance = List.of();
        List<List<Object>> matches = List.of();
        List<List<Object>> evaluations = List.of();
        int queries;
        int lastLimit;
        UUID lastCategory;
        UUID lastTournament;
        Instant from;
        Instant to;
        LocalDate firstDay;
        LocalDate lastDay;

        @Override
        public Optional<Period> seasonPeriod(UUID seasonId) {
            return Optional.ofNullable(seasons.get(seasonId));
        }

        @Override
        public boolean categoryExists(UUID categoryId) {
            return categories.contains(categoryId);
        }

        @Override
        public boolean tournamentExists(UUID tournamentId) {
            return tournaments.contains(tournamentId);
        }

        @Override
        public List<List<Object>> roster(UUID categoryId, int limit) {
            queries++;
            lastLimit = limit;
            lastCategory = categoryId;
            return roster;
        }

        @Override
        public List<List<Object>> attendance(UUID categoryId, Instant from, Instant to, int limit) {
            queries++;
            lastLimit = limit;
            lastCategory = categoryId;
            this.from = from;
            this.to = to;
            return attendance;
        }

        @Override
        public List<List<Object>> matches(UUID categoryId, UUID tournamentId, Instant from, Instant to, int limit) {
            queries++;
            lastLimit = limit;
            lastCategory = categoryId;
            lastTournament = tournamentId;
            this.from = from;
            this.to = to;
            return matches;
        }

        @Override
        public List<List<Object>> evaluations(UUID categoryId, LocalDate firstDay, LocalDate lastDay, int limit) {
            queries++;
            lastLimit = limit;
            lastCategory = categoryId;
            this.firstDay = firstDay;
            this.lastDay = lastDay;
            return evaluations;
        }
    }
}
