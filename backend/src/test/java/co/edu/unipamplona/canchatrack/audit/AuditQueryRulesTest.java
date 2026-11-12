package co.edu.unipamplona.canchatrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.audit.application.AuditCursor;
import co.edu.unipamplona.canchatrack.audit.domain.AuditQueryRules;
import co.edu.unipamplona.canchatrack.audit.domain.FieldError;

class AuditQueryRulesTest {

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    @Test
    void anEmptyFilterIsValid() {
        assertThat(AuditQueryRules.violations(null, null, null, null, null, null)).isEmpty();
    }

    @Test
    void everyViolationIsReportedTogether() {
        Instant from = Instant.parse("2026-05-02T00:00:00Z");
        List<FieldError> errors = AuditQueryRules.violations("A".repeat(81), "e".repeat(61), "MAYBE", from, from, 0);

        assertThat(fields(errors)).containsExactly("action", "entityType", "outcome", "to", "limit");
    }

    @Test
    void theOutcomeMustBeOneOfTheKnownValues() {
        assertThat(AuditQueryRules.violations(null, null, "SUCCESS", null, null, null)).isEmpty();
        assertThat(AuditQueryRules.violations(null, null, "DENIED", null, null, null)).isEmpty();
        assertThat(AuditQueryRules.violations(null, null, "FAILURE", null, null, null)).isEmpty();
        assertThat(fields(AuditQueryRules.violations(null, null, "success", null, null, null))).containsExactly("outcome");
    }

    @Test
    void theRangeIsHalfOpenSoTheEndMustBeAfterTheStart() {
        Instant from = Instant.parse("2026-05-02T00:00:00Z");
        assertThat(AuditQueryRules.violations(null, null, null, from, from.plusSeconds(1), null)).isEmpty();
        assertThat(fields(AuditQueryRules.violations(null, null, null, from, from.minusSeconds(1), null))).containsExactly("to");
        assertThat(AuditQueryRules.violations(null, null, null, from, null, null)).isEmpty();
    }

    @Test
    void thePageSizeStaysBetweenOneAndTheMaximum() {
        assertThat(AuditQueryRules.violations(null, null, null, null, null, 1)).isEmpty();
        assertThat(AuditQueryRules.violations(null, null, null, null, null, AuditQueryRules.MAX_LIMIT)).isEmpty();
        assertThat(fields(AuditQueryRules.violations(null, null, null, null, null, AuditQueryRules.MAX_LIMIT + 1))).containsExactly("limit");
        assertThat(fields(AuditQueryRules.violations(null, null, null, null, null, -5))).containsExactly("limit");
    }

    @Test
    void aCursorSurvivesAnEncodeParseRoundTripWithMicrosecondPrecision() {
        AuditCursor cursor = new AuditCursor(Instant.parse("2026-11-12T20:31:06.293699Z"), UUID.randomUUID());

        assertThat(AuditCursor.parse(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void aMalformedCursorIsRejected() {
        assertThatThrownBy(() -> AuditCursor.parse("no-es-un-cursor")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditCursor.parse("")).isInstanceOf(IllegalArgumentException.class);
        // Base64 válido pero sin separador, y con instante o UUID ilegibles.
        String noSeparator = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("abc".getBytes());
        assertThatThrownBy(() -> AuditCursor.parse(noSeparator)).isInstanceOf(IllegalArgumentException.class);
        String badInstant = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("ayer|" + UUID.randomUUID()).getBytes());
        assertThatThrownBy(() -> AuditCursor.parse(badInstant)).isInstanceOf(IllegalArgumentException.class);
        String badUuid = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("2026-11-12T20:31:06Z|123".getBytes());
        assertThatThrownBy(() -> AuditCursor.parse(badUuid)).isInstanceOf(IllegalArgumentException.class);
    }
}
