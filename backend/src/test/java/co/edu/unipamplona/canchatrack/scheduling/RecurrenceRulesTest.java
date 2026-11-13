package co.edu.unipamplona.canchatrack.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.scheduling.domain.FieldError;
import co.edu.unipamplona.canchatrack.scheduling.domain.RecurrenceRules;
import co.edu.unipamplona.canchatrack.scheduling.domain.RecurrenceRules.Slot;

/** Una serie semanal repite los días indicados a la misma hora local del club (RF-014). */
class RecurrenceRulesTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate TODAY = LocalDate.of(2027, 3, 1); // lunes
    private static final Set<DayOfWeek> MON_WED = Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
    private static final LocalTime FIVE = LocalTime.of(17, 0);
    private static final LocalTime SEVEN = LocalTime.of(19, 0);

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    @Test
    void expandsEveryMatchingDateInChronologicalOrderStartingAtOne() {
        // lunes 1 a miércoles 17 de marzo de 2027: lun 1, mié 3, lun 8, mié 10, lun 15, mié 17
        List<Slot> slots = RecurrenceRules.expand(MON_WED, TODAY, LocalDate.of(2027, 3, 17), FIVE, SEVEN, BOGOTA);

        assertThat(slots).hasSize(6);
        assertThat(slots.stream().map(Slot::position).toList()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(slots.get(0).startAt()).isEqualTo(Instant.parse("2027-03-01T22:00:00Z"));   // 17:00 en Bogotá (UTC-5)
        assertThat(slots.get(0).endAt()).isEqualTo(Instant.parse("2027-03-02T00:00:00Z"));
        assertThat(slots.get(1).startAt()).isEqualTo(Instant.parse("2027-03-03T22:00:00Z"));
        assertThat(slots.get(5).startAt()).isEqualTo(Instant.parse("2027-03-17T22:00:00Z"));
    }

    @Test
    void theLocalTimeStaysTheSameAcrossADaylightSavingChange() {
        // En Nueva York el desfase con UTC cambia el 14 de marzo de 2027: 17:00 local es 22:00Z antes y 21:00Z después.
        ZoneId newYork = ZoneId.of("America/New_York");
        List<Slot> slots = RecurrenceRules.expand(Set.of(DayOfWeek.MONDAY), LocalDate.of(2027, 3, 8), LocalDate.of(2027, 3, 22),
                FIVE, SEVEN, newYork);

        assertThat(slots).hasSize(3);
        assertThat(slots.get(0).startAt()).isEqualTo(Instant.parse("2027-03-08T22:00:00Z"));
        assertThat(slots.get(2).startAt()).isEqualTo(Instant.parse("2027-03-22T21:00:00Z"));
    }

    @Test
    void bothEndDatesAreIncluded() {
        assertThat(RecurrenceRules.expand(Set.of(DayOfWeek.MONDAY), TODAY, TODAY, FIVE, SEVEN, BOGOTA)).hasSize(1);
        assertThat(RecurrenceRules.expand(Set.of(DayOfWeek.MONDAY), TODAY, TODAY.plusDays(7), FIVE, SEVEN, BOGOTA)).hasSize(2);
        assertThat(RecurrenceRules.expand(Set.of(DayOfWeek.MONDAY), TODAY, TODAY.plusDays(6), FIVE, SEVEN, BOGOTA)).hasSize(1);
    }

    @Test
    void aValidSeriesPasses() {
        assertThat(RecurrenceRules.violations(MON_WED, TODAY, TODAY.plusDays(30), FIVE, SEVEN, TODAY, BOGOTA)).isEmpty();
        assertThat(RecurrenceRules.violations(MON_WED, TODAY, TODAY.plusDays(30), FIVE, SEVEN, TODAY.minusDays(3), BOGOTA)).isEmpty();
    }

    @Test
    void missingValuesAreAllReportedTogether() {
        assertThat(fields(RecurrenceRules.violations(null, null, null, null, null, TODAY, BOGOTA)))
                .containsExactly("weekdays", "startDate", "endDate", "startTime", "endTime");
        assertThat(fields(RecurrenceRules.violations(Set.of(), TODAY, TODAY.plusDays(7), FIVE, SEVEN, TODAY, BOGOTA)))
                .containsExactly("weekdays");
    }

    @Test
    void theStartCannotBeInThePastAndTheEndCannotPrecedeTheStart() {
        assertThat(fields(RecurrenceRules.violations(MON_WED, TODAY.minusDays(1), TODAY.plusDays(30), FIVE, SEVEN, TODAY, BOGOTA)))
                .containsExactly("startDate");
        assertThat(fields(RecurrenceRules.violations(MON_WED, TODAY.plusDays(5), TODAY.plusDays(4), FIVE, SEVEN, TODAY, BOGOTA)))
                .containsExactly("endDate");
    }

    @Test
    void theTimesMustFormARealSameDaySession() {
        assertThat(fields(RecurrenceRules.violations(MON_WED, TODAY, TODAY.plusDays(30), SEVEN, FIVE, TODAY, BOGOTA)))
                .containsExactly("endTime");
        assertThat(fields(RecurrenceRules.violations(MON_WED, TODAY, TODAY.plusDays(30), FIVE, FIVE, TODAY, BOGOTA)))
                .containsExactly("endTime");
        assertThat(fields(RecurrenceRules.violations(MON_WED, TODAY, TODAY.plusDays(30), LocalTime.of(6, 0), LocalTime.of(18, 30), TODAY, BOGOTA)))
                .containsExactly("endTime");
    }

    @Test
    void aSeriesWithNoMatchingDateIsRejected() {
        // del lunes 1 al martes 2 solo hay un lunes; pedir solo sábados no encuentra ninguna fecha
        List<FieldError> errors = RecurrenceRules.violations(Set.of(DayOfWeek.SATURDAY), TODAY, TODAY.plusDays(1), FIVE, SEVEN, TODAY, BOGOTA);

        assertThat(fields(errors)).containsExactly("weekdays");
        assertThat(errors.get(0).message()).contains("Ninguna fecha");
    }

    @Test
    void theNumberOfSessionsIsCapped() {
        // todos los días durante 90 días son 91 sesiones
        Set<DayOfWeek> everyDay = Set.of(DayOfWeek.values());
        List<FieldError> errors = RecurrenceRules.violations(everyDay, TODAY, TODAY.plusDays(90), FIVE, SEVEN, TODAY, BOGOTA);

        assertThat(fields(errors)).containsExactly("endDate");
        assertThat(errors.get(0).message()).contains("60", "91");
        // exactamente 60 sesiones es válido: 60 días seguidos
        assertThat(RecurrenceRules.violations(everyDay, TODAY, TODAY.plusDays(59), FIVE, SEVEN, TODAY, BOGOTA)).isEmpty();
        assertThat(RecurrenceRules.violations(everyDay, TODAY, TODAY.plusDays(60), FIVE, SEVEN, TODAY, BOGOTA)).hasSize(1);
    }

    @Test
    void theSpanIsCappedBeforeAnythingIsExpanded() {
        List<FieldError> errors = RecurrenceRules.violations(Set.of(DayOfWeek.MONDAY), TODAY, TODAY.plusDays(367), FIVE, SEVEN, TODAY, BOGOTA);

        assertThat(fields(errors)).containsExactly("endDate");
        assertThat(errors.get(0).message()).contains("366");
        assertThat(RecurrenceRules.violations(Set.of(DayOfWeek.MONDAY), TODAY, TODAY.plusDays(366), FIVE, SEVEN, TODAY, BOGOTA)).isEmpty();
    }
}
