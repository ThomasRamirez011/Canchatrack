package co.edu.unipamplona.canchatrack.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.attendance.domain.AttendanceRules;

class AttendanceRulesTest {

    @Test
    void validMarksPassAndInvalidOnesExplainWhy() {
        UUID id = UUID.randomUUID();
        assertThat(AttendanceRules.markViolations(id, id, "late", false)).isEmpty();
        assertThat(AttendanceRules.markViolations(id, id, "EXCUSED", true)).isEmpty();
        assertThat(AttendanceRules.markViolations(null, null, "NADA", false)).hasSize(3);
        assertThat(AttendanceRules.markViolations(id, id, "PRESENT", true)).hasSize(1);
    }

    @Test
    void correctionsNeedAStatusAndAReason() {
        assertThat(AttendanceRules.correctionViolations("EXCUSED", true, "Certificado médico")).isEmpty();
        assertThat(AttendanceRules.correctionViolations("XX", false, " ")).extracting(e -> e.field()).containsExactly("status", "reason");
        assertThat(AttendanceRules.correctionViolations("PRESENT", true, "motivo")).extracting(e -> e.field()).containsExactly("excuseAccepted");
    }

    @Test
    void percentageExcludesAcceptedExcusesAndRoundsToOneDecimal() {
        assertThat(AttendanceRules.percentage(8, 10, 0)).isEqualByComparingTo("80.0");
        assertThat(AttendanceRules.percentage(8, 10, 2)).isEqualByComparingTo("100.0");
        assertThat(AttendanceRules.percentage(2, 3, 0)).isEqualByComparingTo(new BigDecimal("66.7"));
        assertThat(AttendanceRules.percentage(0, 0, 0)).isNull();
        assertThat(AttendanceRules.percentage(0, 2, 2)).isNull();
    }
}
