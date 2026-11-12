package co.edu.unipamplona.canchatrack.evaluations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.evaluations.domain.EvaluationRules;
import co.edu.unipamplona.canchatrack.evaluations.domain.FieldError;
import co.edu.unipamplona.canchatrack.evaluations.domain.RoundOutcome;
import co.edu.unipamplona.canchatrack.evaluations.domain.Verdict;

class EvaluationRulesTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    // ---------- unanimidad (DDS 5.1, RN-09, RN-12) ----------

    @Test
    void fewerThanThreeConceptsIsPendingEvenIfAllAreFavorable() {
        assertThat(EvaluationRules.outcome(List.of())).isEqualTo(RoundOutcome.PENDING);
        assertThat(EvaluationRules.outcome(List.of(Verdict.FAVORABLE, Verdict.FAVORABLE))).isEqualTo(RoundOutcome.PENDING);
    }

    @Test
    void threeFavorableConceptsApprove() {
        assertThat(EvaluationRules.outcome(List.of(Verdict.FAVORABLE, Verdict.FAVORABLE, Verdict.FAVORABLE)))
                .isEqualTo(RoundOutcome.APPROVED);
    }

    @Test
    void anyNonFavorableConceptAmongThreeMeansFollowUp() {
        assertThat(EvaluationRules.outcome(List.of(Verdict.FAVORABLE, Verdict.FAVORABLE, Verdict.FOLLOW_UP_REQUIRED)))
                .isEqualTo(RoundOutcome.FOLLOW_UP);
        assertThat(EvaluationRules.outcome(List.of(Verdict.UNFAVORABLE, Verdict.FAVORABLE, Verdict.FAVORABLE)))
                .isEqualTo(RoundOutcome.FOLLOW_UP);
    }

    // ---------- conceptos (RN-11) ----------

    @Test
    void favorableConceptNeedsNoJustification() {
        assertThat(EvaluationRules.decisionViolations("FAVORABLE", null, null, Map.of())).isEmpty();
    }

    @Test
    void nonFavorableConceptNeedsJustificationAndConcreteActions() {
        for (String verdict : List.of("FOLLOW_UP_REQUIRED", "UNFAVORABLE")) {
            assertThat(fields(EvaluationRules.decisionViolations(verdict, "  ", null, null)))
                    .containsExactlyInAnyOrder("justification", "actions");
            assertThat(EvaluationRules.decisionViolations(verdict, "Falta fuerza", "Gimnasio 2 veces por semana", null)).isEmpty();
        }
    }

    @Test
    void unknownOrMissingVerdictIsRejected() {
        assertThat(fields(EvaluationRules.decisionViolations(null, null, null, null))).containsExactly("verdict");
        assertThat(fields(EvaluationRules.decisionViolations("MAYBE", null, null, null))).containsExactly("verdict");
    }

    @Test
    void textsAndCriteriaAreBounded() {
        String tooLong = "x".repeat(EvaluationRules.MAX_TEXT + 1);
        assertThat(fields(EvaluationRules.decisionViolations("FAVORABLE", tooLong, tooLong, null)))
                .containsExactlyInAnyOrder("justification", "actions");
        Map<String, Object> many = new java.util.HashMap<>();
        for (int i = 0; i <= EvaluationRules.MAX_CRITERIA_ENTRIES; i++) {
            many.put("c" + i, 3);
        }
        assertThat(fields(EvaluationRules.decisionViolations("FAVORABLE", null, null, many))).containsExactly("criteria");
        assertThat(fields(EvaluationRules.decisionViolations("FAVORABLE", null, null, Map.of(" ", 1)))).containsExactly("criteria");
    }

    // ---------- periodo y vencimiento ----------

    @Test
    void openingNeedsPeriodAndFutureDueDate() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 5, 31);
        assertThat(EvaluationRules.periodViolations(start, end, NOW.plus(10, ChronoUnit.DAYS), true, NOW)).isEmpty();
        assertThat(fields(EvaluationRules.periodViolations(null, null, null, true, NOW)))
                .containsExactlyInAnyOrder("periodStart", "periodEnd", "dueAt");
        assertThat(fields(EvaluationRules.periodViolations(end, start, NOW.plusSeconds(60), true, NOW))).containsExactly("periodEnd");
        assertThat(fields(EvaluationRules.periodViolations(start, end, NOW.minusSeconds(1), true, NOW))).containsExactly("dueAt");
        assertThat(fields(EvaluationRules.periodViolations(start, end, NOW, true, NOW))).containsExactly("dueAt");
    }

    @Test
    void changingThePeriodMayKeepTheCurrentDueDate() {
        assertThat(EvaluationRules.periodViolations(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31), null, false, NOW)).isEmpty();
    }

    // ---------- cierre y motivo ----------

    @Test
    void closingWithoutPromotionNeedsAFollowUpPlan() {
        assertThat(fields(EvaluationRules.followUpPlanViolations(null))).containsExactly("followUpPlan");
        assertThat(fields(EvaluationRules.followUpPlanViolations("   "))).containsExactly("followUpPlan");
        assertThat(EvaluationRules.followUpPlanViolations("Refuerzo técnico quincenal")).isEmpty();
    }

    @Test
    void periodChangeNeedsAReason() {
        assertThat(fields(EvaluationRules.reasonViolations(" "))).containsExactly("reason");
        assertThat(EvaluationRules.reasonViolations("El torneo se aplazó")).isEmpty();
    }
}
