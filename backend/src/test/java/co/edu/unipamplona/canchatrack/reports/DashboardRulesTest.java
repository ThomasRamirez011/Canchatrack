package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.reports.domain.DashboardRules;

class DashboardRulesTest {

    @Test
    void percentRoundsHalfUpAndNeverExceedsOneHundred() {
        assertThat(DashboardRules.percent(1L, 3L)).isEqualTo(33);
        assertThat(DashboardRules.percent(2L, 3L)).isEqualTo(67);
        assertThat(DashboardRules.percent(1L, 8L)).isEqualTo(13); // 12,5 sube
        assertThat(DashboardRules.percent(5L, 4L)).isEqualTo(100);
    }

    @Test
    void percentIsZeroWithoutBaseOrWithNothingDone() {
        assertThat(DashboardRules.percent(3L, 0L)).isZero();
        assertThat(DashboardRules.percent(0L, 10L)).isZero();
        assertThat(DashboardRules.percent(-1L, 10L)).isZero();
    }

    @Test
    void moneyPercentFollowsTheSameRules() {
        assertThat(DashboardRules.percent(new BigDecimal("140000"), new BigDecimal("280000"))).isEqualTo(50);
        assertThat(DashboardRules.percent(new BigDecimal("1"), new BigDecimal("3"))).isEqualTo(33);
        assertThat(DashboardRules.percent(new BigDecimal("300"), new BigDecimal("200"))).isEqualTo(100);
        assertThat(DashboardRules.percent(BigDecimal.ZERO, new BigDecimal("200"))).isZero();
        assertThat(DashboardRules.percent(new BigDecimal("10"), BigDecimal.ZERO)).isZero();
        assertThat(DashboardRules.percent(null, new BigDecimal("10"))).isZero();
        assertThat(DashboardRules.percent(new BigDecimal("10"), null)).isZero();
    }

    @Test
    void aPlayerIsEnabledOnlyWhenActiveAndNotOverdue() {
        assertThat(DashboardRules.enabled("ACTIVE", false)).isTrue();
        assertThat(DashboardRules.enabled("ACTIVE", true)).isFalse();
        assertThat(DashboardRules.enabled("IN_EVALUATION", false)).isFalse();
        assertThat(DashboardRules.enabled(null, false)).isFalse();
    }
}
