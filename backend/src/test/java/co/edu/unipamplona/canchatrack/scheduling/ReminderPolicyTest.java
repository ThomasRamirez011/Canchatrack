package co.edu.unipamplona.canchatrack.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.scheduling.domain.ReminderPolicy;

/** Cuándo corresponde cada recordatorio (RF-019) y cómo se valida su configuración. */
class ReminderPolicyTest {

    private static final Instant START = Instant.parse("2026-12-01T22:00:00Z");
    private static final List<Integer> HOURS = List.of(2, 24);
    private static final Instant LONG_AGO = Instant.parse("2026-11-01T00:00:00Z");

    private static Instant before(Duration d) {
        return START.minus(d);
    }

    @Test
    void nothingIsSentWhileTheEventIsMoreThanTheLargestStageAway() {
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(25)), START, LONG_AGO, HOURS)).isEmpty();
    }

    @Test
    void theFirstStageStartsExactlyAtTheConfiguredHours() {
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(24)), START, LONG_AGO, HOURS).getAsInt()).isEqualTo(24);
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(10)), START, LONG_AGO, HOURS).getAsInt()).isEqualTo(24);
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(2).plusMinutes(1)), START, LONG_AGO, HOURS).getAsInt()).isEqualTo(24);
    }

    @Test
    void theSecondStageTakesOverInsideTheLastTwoHours() {
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(2)), START, LONG_AGO, HOURS).getAsInt()).isEqualTo(2);
        assertThat(ReminderPolicy.stage(before(Duration.ofMinutes(5)), START, LONG_AGO, HOURS).getAsInt()).isEqualTo(2);
    }

    @Test
    void nothingIsSentOnceTheEventHasStarted() {
        assertThat(ReminderPolicy.stage(START, START, LONG_AGO, HOURS)).isEmpty();
        assertThat(ReminderPolicy.stage(START.plusSeconds(1), START, LONG_AGO, HOURS)).isEmpty();
    }

    @Test
    void anEventScheduledInsideAStageDoesNotGetThatReminder() {
        // programado 10 h antes: ya sabían la fecha, no hay recordatorio de 24 h; sí el de 2 h
        Instant created = before(Duration.ofHours(10));
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(9)), START, created, HOURS)).isEmpty();
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(1)), START, created, HOURS).getAsInt()).isEqualTo(2);
        // programado 1 h antes: ningún recordatorio
        assertThat(ReminderPolicy.stage(before(Duration.ofMinutes(30)), START, before(Duration.ofHours(1)), HOURS)).isEmpty();
    }

    @Test
    void creationExactlyAtTheStageBoundaryStillGetsTheReminder() {
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(23)), START, before(Duration.ofHours(24)), HOURS).getAsInt())
                .isEqualTo(24);
    }

    @Test
    void aSingleStageWorks() {
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(5)), START, LONG_AGO, List.of(6)).getAsInt()).isEqualTo(6);
        assertThat(ReminderPolicy.stage(before(Duration.ofHours(5)), START, LONG_AGO, List.of())).isEmpty();
    }

    @Test
    void parsesSortsAndDeduplicatesTheConfiguredHours() {
        assertThat(ReminderPolicy.parseHours("24,2")).containsExactly(2, 24);
        assertThat(ReminderPolicy.parseHours(" 48 , 24, 24 ,1")).containsExactly(1, 24, 48);
        assertThat(ReminderPolicy.parseHours("")).isEmpty();
        assertThat(ReminderPolicy.parseHours(null)).isEmpty();
    }

    @Test
    void rejectsInvalidHoursReportingEveryProblem() {
        assertThatThrownBy(() -> ReminderPolicy.parseHours("0,x,200,24"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 está fuera")
                .hasMessageContaining("'x'")
                .hasMessageContaining("200 está fuera");
        assertThatThrownBy(() -> ReminderPolicy.parseHours("1,2,3,4,5,6"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("máximo 5");
    }

    @Test
    void theErrorNamesTheConfiguredProperty() {
        assertThatThrownBy(() -> ReminderPolicy.parseHours("x", "canchatrack.scheduling.reminders.match-hours"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canchatrack.scheduling.reminders.match-hours");
    }
}
