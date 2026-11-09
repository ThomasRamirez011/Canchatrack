package co.edu.unipamplona.canchatrack.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.payments.domain.FieldError;
import co.edu.unipamplona.canchatrack.payments.domain.PaymentRules;

class PaymentRulesTest {

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    @Test
    void aValidFeePasses() {
        assertThat(PaymentRules.feeViolations("monthly", "Mensualidad", new BigDecimal("80000.50"),
                LocalDate.of(2027, 1, 1), null)).isEmpty();
    }

    @Test
    void feeErrorsAreAccumulated() {
        assertThat(fields(PaymentRules.feeViolations("OTRO", " ", BigDecimal.ZERO, null, null)))
                .containsExactly("concept", "name", "amount", "validFrom");
        assertThat(fields(PaymentRules.feeViolations("MONTHLY", "x", new BigDecimal("10.123"),
                LocalDate.of(2027, 2, 1), LocalDate.of(2027, 1, 1)))).containsExactly("amount", "validTo");
    }

    @Test
    void paymentAmountMethodAndReferenceAreChecked() {
        assertThat(PaymentRules.paymentViolations(new BigDecimal("100.00"), "cash", null)).isEmpty();
        assertThat(fields(PaymentRules.paymentViolations(new BigDecimal("-1"), "BITCOIN", "r".repeat(101))))
                .containsExactly("amount", "method", "reference");
        assertThat(fields(PaymentRules.paymentViolations(null, null, null))).containsExactly("amount", "method");
        assertThat(fields(PaymentRules.paymentViolations(new BigDecimal("1000000000.00"), "CASH", null))).containsExactly("amount");
    }

    @Test
    void voidNeedsAReason() {
        assertThat(PaymentRules.voidViolations("Error de captura")).isEmpty();
        assertThat(fields(PaymentRules.voidViolations("  "))).containsExactly("reason");
        assertThat(fields(PaymentRules.voidViolations("x".repeat(2001)))).containsExactly("reason");
    }

    @Test
    void monthlyPeriodMustBeTheFirstDayOfTheMonth() {
        assertThat(PaymentRules.monthlyViolations(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 10))).isEmpty();
        assertThat(fields(PaymentRules.monthlyViolations(LocalDate.of(2027, 3, 2), null))).containsExactly("period", "dueDate");
    }
}
