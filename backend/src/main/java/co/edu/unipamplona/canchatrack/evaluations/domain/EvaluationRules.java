package co.edu.unipamplona.canchatrack.evaluations.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reglas puras de la evaluación (RN-08..RN-12, DDS 5.1). Sin acceso a datos: reciben los valores y devuelven los errores
 * acumulados o el resultado de la unanimidad.
 */
public final class EvaluationRules {

    /** Tamaño del panel (DAD SUP-04). */
    public static final int PANEL_SIZE = 3;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_CRITERIA_ENTRIES = 40;
    public static final int MAX_CRITERIA_KEY = 100;

    public static final String FIELD_VERDICT = "verdict";
    public static final String FIELD_JUSTIFICATION = "justification";
    public static final String FIELD_ACTIONS = "actions";

    private EvaluationRules() {
    }

    /**
     * Periodo y vencimiento de una ronda (RN-12). Al abrir, el vencimiento es obligatorio; al cambiar el periodo puede
     * omitirse para conservar el actual.
     */
    public static List<FieldError> periodViolations(LocalDate start, LocalDate end, Instant dueAt, boolean dueRequired,
                                                    Instant now) {
        List<FieldError> errors = new ArrayList<>();
        if (start == null) {
            errors.add(new FieldError("periodStart", "El inicio del periodo es obligatorio."));
        }
        if (end == null) {
            errors.add(new FieldError("periodEnd", "El fin del periodo es obligatorio."));
        }
        if (start != null && end != null && start.isAfter(end)) {
            errors.add(new FieldError("periodEnd", "El fin del periodo no puede ser anterior a su inicio."));
        }
        if (dueAt == null) {
            if (dueRequired) {
                errors.add(new FieldError("dueAt", "El vencimiento de la ronda es obligatorio."));
            }
        } else if (!dueAt.isAfter(now)) {
            errors.add(new FieldError("dueAt", "El vencimiento debe ser una fecha futura."));
        }
        return errors;
    }

    /** RN-11: un concepto distinto de favorable exige justificación escrita y acciones concretas. */
    public static List<FieldError> decisionViolations(String verdict, String justification, String actions,
                                                      Map<String, ?> criteria) {
        List<FieldError> errors = new ArrayList<>();
        Verdict parsed = Verdict.parse(verdict).orElse(null);
        if (parsed == null) {
            errors.add(new FieldError(FIELD_VERDICT, "El concepto debe ser FAVORABLE, FOLLOW_UP_REQUIRED o UNFAVORABLE."));
        } else if (parsed != Verdict.FAVORABLE) {
            if (isBlank(justification)) {
                errors.add(new FieldError(FIELD_JUSTIFICATION, "Un concepto no favorable o con seguimiento exige justificación."));
            }
            if (isBlank(actions)) {
                errors.add(new FieldError(FIELD_ACTIONS, "Un concepto no favorable o con seguimiento exige acciones concretas."));
            }
        }
        if (justification != null && justification.length() > MAX_TEXT) {
            errors.add(new FieldError(FIELD_JUSTIFICATION, "La justificación no puede superar " + MAX_TEXT + " caracteres."));
        }
        if (actions != null && actions.length() > MAX_TEXT) {
            errors.add(new FieldError(FIELD_ACTIONS, "Las acciones no pueden superar " + MAX_TEXT + " caracteres."));
        }
        if (criteria != null) {
            if (criteria.size() > MAX_CRITERIA_ENTRIES) {
                errors.add(new FieldError("criteria", "Se admiten como máximo " + MAX_CRITERIA_ENTRIES + " criterios."));
            }
            for (String key : criteria.keySet()) {
                if (key == null || key.isBlank() || key.length() > MAX_CRITERIA_KEY) {
                    errors.add(new FieldError("criteria", "Cada criterio necesita un nombre de hasta " + MAX_CRITERIA_KEY + " caracteres."));
                    break;
                }
            }
        }
        return errors;
    }

    /** DDS 5.1: con menos de tres conceptos vigentes no hay resultado; con tres, unanimidad o seguimiento. */
    public static RoundOutcome outcome(Collection<Verdict> currentVerdicts) {
        if (currentVerdicts.size() < PANEL_SIZE) {
            return RoundOutcome.PENDING;
        }
        return currentVerdicts.stream().allMatch(v -> v == Verdict.FAVORABLE) ? RoundOutcome.APPROVED : RoundOutcome.FOLLOW_UP;
    }

    /** El plan de seguimiento lo define el coordinador al cerrar sin ascenso (RN-12). */
    public static List<FieldError> followUpPlanViolations(String plan) {
        List<FieldError> errors = new ArrayList<>();
        if (isBlank(plan)) {
            errors.add(new FieldError("followUpPlan", "Cerrar sin ascenso exige un plan de seguimiento."));
        } else if (plan.length() > MAX_TEXT) {
            errors.add(new FieldError("followUpPlan", "El plan de seguimiento no puede superar " + MAX_TEXT + " caracteres."));
        }
        return errors;
    }

    /** Cambiar el periodo de una ronda abierta exige motivo (queda auditado). */
    public static List<FieldError> reasonViolations(String reason) {
        List<FieldError> errors = new ArrayList<>();
        if (isBlank(reason)) {
            errors.add(new FieldError("reason", "El motivo es obligatorio."));
        } else if (reason.length() > MAX_TEXT) {
            errors.add(new FieldError("reason", "El motivo no puede superar " + MAX_TEXT + " caracteres."));
        }
        return errors;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
