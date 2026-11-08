package co.edu.unipamplona.canchatrack.players.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Reglas de forma de una categoría (RN-04). Los rangos de edad y los cupos los define el club (DP-01, pendiente): no hay
 * valores quemados, solo cotas de cordura para rechazar errores de captura.
 */
public final class CategoryRules {

    public static final int MAX_NAME = 80;
    public static final int MIN_BIRTH_YEAR = 1950;
    public static final int MAX_BIRTH_YEAR = 2100;
    public static final int MAX_CAPACITY = 500;

    private CategoryRules() {
    }

    public static List<FieldError> violations(String name, Integer birthYearMin, Integer birthYearMax, Integer capacity) {
        List<FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("name", "El nombre es obligatorio."));
        } else if (name.trim().length() > MAX_NAME) {
            errors.add(new FieldError("name", "El nombre no puede superar " + MAX_NAME + " caracteres."));
        }
        year(errors, "birthYearMin", "El primer año de nacimiento", birthYearMin);
        year(errors, "birthYearMax", "El último año de nacimiento", birthYearMax);
        if (birthYearMin != null && birthYearMax != null && birthYearMin > birthYearMax) {
            errors.add(new FieldError("birthYearMax", "El último año no puede ser anterior al primero."));
        }
        if (capacity == null) {
            errors.add(new FieldError("capacity", "El cupo es obligatorio."));
        } else if (capacity < 1 || capacity > MAX_CAPACITY) {
            errors.add(new FieldError("capacity", "El cupo debe estar entre 1 y " + MAX_CAPACITY + "."));
        }
        return errors;
    }

    private static void year(List<FieldError> errors, String field, String label, Integer value) {
        if (value == null) {
            errors.add(new FieldError(field, label + " es obligatorio."));
        } else if (value < MIN_BIRTH_YEAR || value > MAX_BIRTH_YEAR) {
            errors.add(new FieldError(field, label + " debe estar entre " + MIN_BIRTH_YEAR + " y " + MAX_BIRTH_YEAR + "."));
        }
    }
}
