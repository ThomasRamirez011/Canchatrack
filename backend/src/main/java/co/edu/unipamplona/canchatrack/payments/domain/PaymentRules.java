package co.edu.unipamplona.canchatrack.payments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reglas puras de tarifas, pagos y anulaciones (RN-16). Los conceptos y los métodos de pago son provisionales hasta que
 * el club defina su política de cobro (DP-05).
 */
public final class PaymentRules {

    public static final Set<String> CONCEPTS = Set.of("ENROLLMENT", "MONTHLY", "OTHER");
    public static final Set<String> METHODS = Set.of("CASH", "TRANSFER", "CARD", "OTHER");
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
    public static final int MAX_NAME = 120;
    public static final int MAX_REFERENCE = 100;
    public static final int MAX_REASON = 2000;

    private PaymentRules() {
    }

    public static List<FieldError> feeViolations(String concept, String name, BigDecimal amount, LocalDate validFrom,
                                                 LocalDate validTo) {
        List<FieldError> errors = new ArrayList<>();
        if (concept == null || !CONCEPTS.contains(concept.trim().toUpperCase())) {
            errors.add(new FieldError("concept", "El concepto debe ser uno de: " + String.join(", ", CONCEPTS.stream().sorted().toList()) + "."));
        }
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("name", "El nombre es obligatorio."));
        } else if (name.trim().length() > MAX_NAME) {
            errors.add(new FieldError("name", "El nombre no puede superar " + MAX_NAME + " caracteres."));
        }
        amountError("amount", amount).ifPresent(errors::add);
        if (validFrom == null) {
            errors.add(new FieldError("validFrom", "La fecha de inicio de vigencia es obligatoria."));
        } else if (validTo != null && validTo.isBefore(validFrom)) {
            errors.add(new FieldError("validTo", "El fin de vigencia no puede ser anterior al inicio."));
        }
        return errors;
    }

    public static List<FieldError> paymentViolations(BigDecimal amount, String method, String reference) {
        List<FieldError> errors = new ArrayList<>();
        amountError("amount", amount).ifPresent(errors::add);
        if (method == null || !METHODS.contains(method.trim().toUpperCase())) {
            errors.add(new FieldError("method", "El método debe ser uno de: " + String.join(", ", METHODS.stream().sorted().toList()) + "."));
        }
        if (reference != null && reference.trim().length() > MAX_REFERENCE) {
            errors.add(new FieldError("reference", "La referencia no puede superar " + MAX_REFERENCE + " caracteres."));
        }
        return errors;
    }

    public static List<FieldError> voidViolations(String reason) {
        List<FieldError> errors = new ArrayList<>();
        if (reason == null || reason.isBlank()) {
            errors.add(new FieldError("reason", "El motivo de la anulación es obligatorio."));
        } else if (reason.trim().length() > MAX_REASON) {
            errors.add(new FieldError("reason", "El motivo no puede superar " + MAX_REASON + " caracteres."));
        }
        return errors;
    }

    /** Un periodo mensual es siempre el primer día del mes. */
    public static List<FieldError> monthlyViolations(LocalDate period, LocalDate dueDate) {
        List<FieldError> errors = new ArrayList<>();
        if (period == null) {
            errors.add(new FieldError("period", "El periodo es obligatorio."));
        } else if (period.getDayOfMonth() != 1) {
            errors.add(new FieldError("period", "El periodo debe ser el primer día del mes."));
        }
        if (dueDate == null) {
            errors.add(new FieldError("dueDate", "La fecha de vencimiento es obligatoria."));
        }
        return errors;
    }

    private static java.util.Optional<FieldError> amountError(String field, BigDecimal amount) {
        if (amount == null) {
            return java.util.Optional.of(new FieldError(field, "El monto es obligatorio."));
        }
        if (amount.signum() <= 0) {
            return java.util.Optional.of(new FieldError(field, "El monto debe ser mayor que cero."));
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            return java.util.Optional.of(new FieldError(field, "El monto admite como máximo dos decimales."));
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            return java.util.Optional.of(new FieldError(field, "El monto es demasiado grande."));
        }
        return java.util.Optional.empty();
    }
}
