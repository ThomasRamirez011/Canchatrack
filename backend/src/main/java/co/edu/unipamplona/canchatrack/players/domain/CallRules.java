package co.edu.unipamplona.canchatrack.players.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Reglas de forma de temporadas y convocatorias (RF-043). No dependen del reloj: la vigencia la decide la base. */
public final class CallRules {

    public static final int MAX_SEASON_NAME = 100;
    public static final int MAX_CALL_NAME = 150;
    public static final int MAX_INSTRUCTIONS = 4000;
    public static final int MAX_REASON = 500;

    private CallRules() {
    }

    public static List<FieldError> seasonViolations(String name, Instant startAt, Instant endAt) {
        List<FieldError> errors = new ArrayList<>();
        textRequired(errors, "name", "El nombre", name, MAX_SEASON_NAME);
        window(errors, startAt, endAt);
        return errors;
    }

    public static List<FieldError> callViolations(String name, Instant startAt, Instant endAt, String instructions) {
        List<FieldError> errors = new ArrayList<>();
        textRequired(errors, "name", "El nombre", name, MAX_CALL_NAME);
        window(errors, startAt, endAt);
        if (instructions != null && instructions.length() > MAX_INSTRUCTIONS) {
            errors.add(new FieldError("instructions", "Las instrucciones no pueden superar " + MAX_INSTRUCTIONS + " caracteres."));
        }
        return errors;
    }

    /** Un motivo es un texto no vacío de hasta {@value #MAX_REASON} caracteres. */
    public static void reasonViolations(List<FieldError> errors, String reason) {
        textRequired(errors, "reason", "El motivo", reason, MAX_REASON);
    }

    private static void textRequired(List<FieldError> errors, String field, String label, String value, int max) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldError(field, label + " es obligatorio."));
        } else if (value.trim().length() > max) {
            errors.add(new FieldError(field, label + " no puede superar " + max + " caracteres."));
        }
    }

    private static void window(List<FieldError> errors, Instant startAt, Instant endAt) {
        if (startAt == null) {
            errors.add(new FieldError("startAt", "La fecha de apertura es obligatoria."));
        }
        if (endAt == null) {
            errors.add(new FieldError("endAt", "La fecha de cierre es obligatoria."));
        }
        if (startAt != null && endAt != null && !startAt.isBefore(endAt)) {
            errors.add(new FieldError("endAt", "La fecha de cierre debe ser posterior a la de apertura."));
        }
    }
}
