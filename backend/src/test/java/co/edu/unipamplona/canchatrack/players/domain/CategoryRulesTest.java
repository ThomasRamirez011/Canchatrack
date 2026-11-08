package co.edu.unipamplona.canchatrack.players.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CategoryRulesTest {

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    @Test
    void aValidCategoryHasNoViolations() {
        assertThat(CategoryRules.violations("Sub-12", 2014, 2015, 20)).isEmpty();
        assertThat(CategoryRules.violations("Única", 2014, 2014, 1)).isEmpty();
    }

    @Test
    void theYearRangeMustBeOrderedAndWithinSaneBounds() {
        assertThat(fields(CategoryRules.violations("X", 2016, 2015, 5))).isEqualTo(List.of("birthYearMax"));
        assertThat(fields(CategoryRules.violations("X", 1800, 2015, 5))).isEqualTo(List.of("birthYearMin"));
        assertThat(fields(CategoryRules.violations("X", 2014, 3000, 5))).isEqualTo(List.of("birthYearMax"));
    }

    @Test
    void capacityAndNameAreBounded() {
        assertThat(fields(CategoryRules.violations("X", 2014, 2015, 0))).isEqualTo(List.of("capacity"));
        assertThat(fields(CategoryRules.violations("X", 2014, 2015, 501))).isEqualTo(List.of("capacity"));
        assertThat(fields(CategoryRules.violations("x".repeat(81), 2014, 2015, 5))).isEqualTo(List.of("name"));
        assertThat(fields(CategoryRules.violations(" ", 2014, 2015, 5))).isEqualTo(List.of("name"));
    }

    @Test
    void everythingMissingIsReportedTogether() {
        assertThat(fields(CategoryRules.violations(null, null, null, null)))
                .isEqualTo(List.of("name", "birthYearMin", "birthYearMax", "capacity"));
    }
}
