package co.edu.unipamplona.canchatrack.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.scheduling.domain.FieldError;
import co.edu.unipamplona.canchatrack.scheduling.domain.SchedulingRules;

class SchedulingRulesTest {

    private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    @Test
    void aValidEventPasses() {
        assertThat(SchedulingRules.eventViolations("training", NOW.plusSeconds(3600), NOW.plusSeconds(7200),
                NOW.plusSeconds(3000), "Portería", null, NOW)).isEmpty();
    }

    @Test
    void eventErrorsAreAccumulated() {
        assertThat(fields(SchedulingRules.eventViolations("PARTIDO", NOW.minusSeconds(60), NOW.minusSeconds(120), NOW, null, null, NOW)))
                .containsExactly("eventType", "startAt", "endAt", "meetingAt");
        assertThat(fields(SchedulingRules.eventViolations("TRAINING", null, null, null, null, null, NOW)))
                .containsExactly("startAt", "endAt");
        assertThat(fields(SchedulingRules.eventViolations("TRAINING", NOW.plusSeconds(60), NOW.plusSeconds(60 + 13 * 3600), null, null, null, NOW)))
                .containsExactly("endAt");
    }

    @Test
    void overlapIsHalfOpenSoBackToBackEventsDoNotConflict() {
        Instant nine = Instant.parse("2027-03-02T09:00:00Z");
        Instant ten = Instant.parse("2027-03-02T10:00:00Z");
        Instant eleven = Instant.parse("2027-03-02T11:00:00Z");
        assertThat(SchedulingRules.overlaps(nine, ten, ten, eleven)).isFalse();
        assertThat(SchedulingRules.overlaps(nine, eleven, ten, eleven)).isTrue();
        assertThat(SchedulingRules.overlaps(ten, eleven, nine, ten.plusSeconds(1))).isTrue();
    }

    @Test
    void venueAndCancellationRules() {
        assertThat(SchedulingRules.venueViolations("Cancha 1", null)).isEmpty();
        assertThat(fields(SchedulingRules.venueViolations(" ", "x".repeat(251)))).containsExactly("name", "address");
        assertThat(SchedulingRules.cancellationViolations("Lluvia")).isEmpty();
        assertThat(fields(SchedulingRules.cancellationViolations(" "))).containsExactly("reason");
    }
}
