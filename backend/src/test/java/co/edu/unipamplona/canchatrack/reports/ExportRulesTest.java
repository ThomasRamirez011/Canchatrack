package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.reports.domain.ExportRules;
import co.edu.unipamplona.canchatrack.reports.domain.ExportRules.Window;
import co.edu.unipamplona.canchatrack.reports.domain.FieldError;

class ExportRulesTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    // ---------- errores de fechas ----------

    @Test
    void withoutSeasonBothDatesAreRequired() {
        assertThat(fields(ExportRules.windowErrors(false, null, null))).containsExactly("from", "to");
        assertThat(fields(ExportRules.windowErrors(false, LocalDate.of(2030, 1, 1), null))).containsExactly("to");
        assertThat(ExportRules.windowErrors(false, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31))).isEmpty();
    }

    @Test
    void withSeasonTheDatesAreOptional() {
        assertThat(ExportRules.windowErrors(true, null, null)).isEmpty();
        assertThat(ExportRules.windowErrors(true, LocalDate.of(2030, 1, 1), null)).isEmpty();
    }

    @Test
    void theEndCannotPrecedeTheStart() {
        List<FieldError> errors = ExportRules.windowErrors(false, LocalDate.of(2030, 2, 10), LocalDate.of(2030, 2, 9));
        assertThat(fields(errors)).containsExactly("to");
        assertThat(ExportRules.windowErrors(true, LocalDate.of(2030, 2, 10), LocalDate.of(2030, 2, 9))).hasSize(1);
        assertThat(ExportRules.windowErrors(false, LocalDate.of(2030, 2, 10), LocalDate.of(2030, 2, 10))).isEmpty();
    }

    @Test
    void anExplicitRangeCannotExceedAYear() {
        LocalDate from = LocalDate.of(2032, 1, 1); // 2032 es bisiesto: 366 días
        assertThat(ExportRules.windowErrors(false, from, LocalDate.of(2032, 12, 31))).isEmpty();
        assertThat(fields(ExportRules.windowErrors(false, from, LocalDate.of(2033, 1, 1)))).containsExactly("to");
        // con temporada no hay tope: el periodo lo fija la temporada
        assertThat(ExportRules.windowErrors(true, from, LocalDate.of(2035, 1, 1))).isEmpty();
    }

    // ---------- ventana ----------

    @Test
    void datesBecomeLocalMidnightsOfTheClubZoneWithTheLastDayIncluded() {
        Window w = ExportRules.window(null, null, LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 28), BOGOTA);
        assertThat(w.start()).isEqualTo(Instant.parse("2030-02-01T05:00:00Z"));
        assertThat(w.end()).isEqualTo(Instant.parse("2030-03-01T05:00:00Z"));
        assertThat(w.firstDay(BOGOTA)).isEqualTo(LocalDate.of(2030, 2, 1));
        assertThat(w.lastDay(BOGOTA)).isEqualTo(LocalDate.of(2030, 2, 28));
    }

    @Test
    void theSeasonSetsTheWindowAndTheDatesOnlyNarrowIt() {
        Instant start = Instant.parse("2030-01-01T05:00:00Z");
        Instant end = Instant.parse("2031-01-01T05:00:00Z");

        Window whole = ExportRules.window(start, end, null, null, BOGOTA);
        assertThat(whole.start()).isEqualTo(start);
        assertThat(whole.end()).isEqualTo(end);
        assertThat(whole.lastDay(BOGOTA)).isEqualTo(LocalDate.of(2030, 12, 31));

        Window narrowed = ExportRules.window(start, end, LocalDate.of(2030, 6, 1), LocalDate.of(2030, 6, 30), BOGOTA);
        assertThat(narrowed.start()).isEqualTo(Instant.parse("2030-06-01T05:00:00Z"));
        assertThat(narrowed.end()).isEqualTo(Instant.parse("2030-07-01T05:00:00Z"));

        // fechas que desbordan la temporada no la amplían
        Window wider = ExportRules.window(start, end, LocalDate.of(2029, 1, 1), LocalDate.of(2032, 1, 1), BOGOTA);
        assertThat(wider.start()).isEqualTo(start);
        assertThat(wider.end()).isEqualTo(end);
    }

    @Test
    void noOverlapLeavesAnEmptyWindow() {
        Instant start = Instant.parse("2030-01-01T05:00:00Z");
        Instant end = Instant.parse("2031-01-01T05:00:00Z");
        Window w = ExportRules.window(start, end, LocalDate.of(2032, 1, 1), null, BOGOTA);
        assertThat(w.start()).isAfterOrEqualTo(w.end());
    }

    @Test
    void fileNamesCarryTheCoveredDays() {
        Window w = ExportRules.window(null, null, LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 28), BOGOTA);
        assertThat(ExportRules.fileName("asistencia", w, BOGOTA)).isEqualTo("asistencia-2030-02-01_2030-02-28.csv");
    }

    @Test
    void yesNoIsSpanish() {
        assertThat(ExportRules.yesNo(true)).isEqualTo("Sí");
        assertThat(ExportRules.yesNo(false)).isEqualTo("No");
    }
}
