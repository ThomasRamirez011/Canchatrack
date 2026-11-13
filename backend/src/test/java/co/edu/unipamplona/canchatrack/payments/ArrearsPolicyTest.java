package co.edu.unipamplona.canchatrack.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.payments.application.ArrearsSettings;
import co.edu.unipamplona.canchatrack.payments.domain.ArrearsPolicy;

class ArrearsPolicyTest {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 5);

    private static int stage(int daysAfterDue, int grace, int interval, int max) {
        return ArrearsPolicy.stage(DUE, DUE.plusDays(daysAfterDue), grace, interval, max).orElse(-1);
    }

    @Test
    void aChargeIsNotInArrearsUntilTheDayAfterItsDueDate() {
        assertThat(stage(-3, 0, 7, 8)).isEqualTo(-1);
        assertThat(stage(0, 0, 7, 8)).isEqualTo(-1);
        assertThat(stage(1, 0, 7, 8)).isEqualTo(0);
    }

    @Test
    void aNoticeGoesOutEveryIntervalWhileTheChargeStaysOpen() {
        assertThat(stage(7, 0, 7, 8)).isEqualTo(0);
        assertThat(stage(8, 0, 7, 8)).isEqualTo(1);
        assertThat(stage(14, 0, 7, 8)).isEqualTo(1);
        assertThat(stage(15, 0, 7, 8)).isEqualTo(2);
        assertThat(stage(22, 0, 3, 8)).isEqualTo(7);
    }

    @Test
    void theGraceDaysMoveTheFirstNoticeBackAndMatchTheEligibilityRule() {
        assertThat(stage(3, 3, 7, 8)).isEqualTo(-1);
        assertThat(stage(4, 3, 7, 8)).isEqualTo(0);
        assertThat(stage(11, 3, 7, 8)).isEqualTo(1);
    }

    @Test
    void theNoticesStopAfterTheMaximum() {
        assertThat(stage(1 + 7 * 7, 0, 7, 8)).isEqualTo(7);
        assertThat(stage(1 + 7 * 8, 0, 7, 8)).isEqualTo(-1);
        assertThat(stage(400, 0, 7, 8)).isEqualTo(-1);
        assertThat(stage(1, 0, 7, 0)).isEqualTo(-1);
    }

    @Test
    void invalidInputsGiveNoNotice() {
        assertThat(ArrearsPolicy.stage(null, DUE, 0, 7, 8)).isEmpty();
        assertThat(ArrearsPolicy.stage(DUE, null, 0, 7, 8)).isEmpty();
        assertThat(ArrearsPolicy.stage(DUE, DUE.plusDays(5), 0, 0, 8)).isEmpty();
    }

    @Test
    void theQueryWindowCoversExactlyTheChargesThatCanStillBeNoticed() {
        LocalDate today = LocalDate.of(2026, 12, 1);
        LocalDate newest = ArrearsPolicy.newestDue(today, 2);
        LocalDate oldest = ArrearsPolicy.oldestDue(today, 2, 7, 8);
        assertThat(ArrearsPolicy.stage(newest, today, 2, 7, 8)).hasValue(0);
        assertThat(ArrearsPolicy.stage(newest.plusDays(1), today, 2, 7, 8)).isEmpty();
        assertThat(ArrearsPolicy.stage(oldest, today, 2, 7, 8)).hasValue(7);
        assertThat(ArrearsPolicy.stage(oldest.minusDays(1), today, 2, 7, 8)).isEmpty();
    }

    @Test
    void invalidSettingsStopTheStartup() {
        ZoneId zone = ZoneId.of("America/Bogota");
        assertThat(new ArrearsSettings(zone, 0, 7, 0, null).appUrl()).isEmpty();
        assertThatThrownBy(() -> new ArrearsSettings(zone, -1, 7, 8, "")).hasMessageContaining("arrears-grace-days");
        assertThatThrownBy(() -> new ArrearsSettings(zone, 0, 0, 8, "")).hasMessageContaining("interval-days");
        assertThatThrownBy(() -> new ArrearsSettings(zone, 0, 7, 100, "")).hasMessageContaining("max-notices");
        assertThatThrownBy(() -> new ArrearsSettings(null, 0, 7, 8, "")).hasMessageContaining("zone-id");
    }
}
