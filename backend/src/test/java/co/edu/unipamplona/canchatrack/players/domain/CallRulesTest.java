package co.edu.unipamplona.canchatrack.players.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class CallRulesTest {

    private static final Instant A = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant B = Instant.parse("2026-12-01T00:00:00Z");

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    @Test
    void aValidCallHasNoViolations() {
        assertThat(CallRules.callViolations("Convocatoria", A, B, "Texto")).isEmpty();
        assertThat(CallRules.seasonViolations("Temporada", A, B)).isEmpty();
    }

    @Test
    void theWindowMustBeOrderedAndComplete() {
        assertThat(fields(CallRules.callViolations("X", B, A, null))).isEqualTo(List.of("endAt"));
        assertThat(fields(CallRules.callViolations("X", A, A, null))).isEqualTo(List.of("endAt"));
        assertThat(fields(CallRules.callViolations("X", null, null, null))).isEqualTo(List.of("startAt", "endAt"));
    }

    @Test
    void textLengthsAreBounded() {
        assertThat(fields(CallRules.callViolations("x".repeat(151), A, B, null))).isEqualTo(List.of("name"));
        assertThat(fields(CallRules.callViolations("x".repeat(150), A, B, "i".repeat(4001)))).isEqualTo(List.of("instructions"));
        assertThat(fields(CallRules.seasonViolations("x".repeat(101), A, B))).isEqualTo(List.of("name"));
        assertThat(fields(CallRules.callViolations("   ", A, B, null))).isEqualTo(List.of("name"));
    }
}
