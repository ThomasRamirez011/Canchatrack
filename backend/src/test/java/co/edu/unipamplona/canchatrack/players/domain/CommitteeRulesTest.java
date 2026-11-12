package co.edu.unipamplona.canchatrack.players.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CommitteeRulesTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 1);

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    private static List<UUID> three() {
        return List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void aCompleteMinuteHasNoViolations() {
        assertThat(CommitteeRules.minuteViolations("ACTA-12", DATE, List.of("Ana Gómez"), UUID.randomUUID())).isEmpty();
    }

    @Test
    void everyMissingMinuteFieldIsReportedTogether() {
        assertThat(fields(CommitteeRules.minuteViolations(" ", null, List.of(), null)))
                .containsExactly("minuteNumber", "meetingDate", "attendees", "minuteFileId");
        assertThat(fields(CommitteeRules.minuteViolations(null, DATE, null, UUID.randomUUID())))
                .containsExactly("minuteNumber", "attendees");
    }

    @Test
    void minuteNumberAndAttendeesHaveSaneBounds() {
        assertThat(fields(CommitteeRules.minuteViolations("X".repeat(31), DATE, List.of("a"), UUID.randomUUID())))
                .containsExactly("minuteNumber");
        assertThat(CommitteeRules.minuteViolations("X".repeat(30), DATE, List.of("a"), UUID.randomUUID())).isEmpty();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            many.add("Asistente " + i);
        }
        assertThat(fields(CommitteeRules.minuteViolations("A", DATE, many, UUID.randomUUID()))).containsExactly("attendees");
        assertThat(fields(CommitteeRules.minuteViolations("A", DATE, List.of("Ana", " "), UUID.randomUUID()))).containsExactly("attendees");
        assertThat(fields(CommitteeRules.minuteViolations("A", DATE, List.of("N".repeat(151)), UUID.randomUUID()))).containsExactly("attendees");
    }

    @Test
    void anAssignmentNeedsCategoryCoachAndExactlyThreeDistinctEvaluators() {
        assertThat(CommitteeRules.assignmentViolations(UUID.randomUUID(), UUID.randomUUID(), three(), null)).isEmpty();
        assertThat(fields(CommitteeRules.assignmentViolations(null, null, null, null)))
                .containsExactly("categoryId", "headCoachId", "evaluatorIds");
        assertThat(fields(CommitteeRules.assignmentViolations(UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID(), UUID.randomUUID()), null))).containsExactly("evaluatorIds");
        List<UUID> four = new ArrayList<>(three());
        four.add(UUID.randomUUID());
        assertThat(fields(CommitteeRules.assignmentViolations(UUID.randomUUID(), UUID.randomUUID(), four, null)))
                .containsExactly("evaluatorIds");
    }

    @Test
    void evaluatorsCannotRepeatOrBeMissing() {
        UUID same = UUID.randomUUID();
        assertThat(fields(CommitteeRules.assignmentViolations(UUID.randomUUID(), UUID.randomUUID(),
                List.of(same, same, UUID.randomUUID()), null))).containsExactly("evaluatorIds");
        List<UUID> withNull = new ArrayList<>();
        withNull.add(UUID.randomUUID());
        withNull.add(null);
        withNull.add(UUID.randomUUID());
        assertThat(fields(CommitteeRules.assignmentViolations(UUID.randomUUID(), UUID.randomUUID(), withNull, null)))
                .containsExactly("evaluatorIds");
    }

    @Test
    void theCapacityExceptionReasonHasAnUpperBound() {
        assertThat(fields(CommitteeRules.assignmentViolations(UUID.randomUUID(), UUID.randomUUID(), three(), "m".repeat(2001))))
                .containsExactly("capacityExceptionReason");
        assertThat(CommitteeRules.assignmentViolations(UUID.randomUUID(), UUID.randomUUID(), three(), "m".repeat(2000))).isEmpty();
    }

    @Test
    void aRejectionRequiresAReason() {
        assertThat(CommitteeRules.rejectionViolations("No cumple el perfil")).isEmpty();
        assertThat(fields(CommitteeRules.rejectionViolations(null))).containsExactly("reason");
        assertThat(fields(CommitteeRules.rejectionViolations("   "))).containsExactly("reason");
        assertThat(fields(CommitteeRules.rejectionViolations("m".repeat(2001)))).containsExactly("reason");
    }

    @Test
    void onlyThePanelFieldsMapToPanelInvalid() {
        assertThat(CommitteeRules.isPanelField("evaluatorIds")).isTrue();
        assertThat(CommitteeRules.isPanelField("headCoachId")).isTrue();
        assertThat(CommitteeRules.isPanelField("categoryId")).isFalse();
        assertThat(CommitteeRules.isPanelField("minuteNumber")).isFalse();
    }
}
