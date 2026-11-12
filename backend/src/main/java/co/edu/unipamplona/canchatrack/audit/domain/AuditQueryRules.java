package co.edu.unipamplona.canchatrack.audit.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reglas de la consulta de auditoría (CU-14, RF-051). Puras: devuelven todos los errores juntos. */
public final class AuditQueryRules {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_ACTION = 80;
    public static final int MAX_ENTITY_TYPE = 60;
    public static final Set<String> OUTCOMES = Set.of("SUCCESS", "DENIED", "FAILURE");

    private AuditQueryRules() {
    }

    /** {@code from} es inclusivo y {@code to} exclusivo; el tamaño de página va de 1 a {@link #MAX_LIMIT}. */
    public static List<FieldError> violations(String action, String entityType, String outcome, Instant from, Instant to,
                                              Integer limit) {
        List<FieldError> errors = new ArrayList<>();
        if (action != null && action.length() > MAX_ACTION) {
            errors.add(new FieldError("action", "La acción no puede superar " + MAX_ACTION + " caracteres."));
        }
        if (entityType != null && entityType.length() > MAX_ENTITY_TYPE) {
            errors.add(new FieldError("entityType", "El tipo de entidad no puede superar " + MAX_ENTITY_TYPE + " caracteres."));
        }
        if (outcome != null && !OUTCOMES.contains(outcome)) {
            errors.add(new FieldError("outcome", "El resultado debe ser uno de: DENIED, FAILURE, SUCCESS."));
        }
        if (from != null && to != null && !from.isBefore(to)) {
            errors.add(new FieldError("to", "La fecha final debe ser posterior a la inicial."));
        }
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            errors.add(new FieldError("limit", "El tamaño de página debe estar entre 1 y " + MAX_LIMIT + "."));
        }
        return errors;
    }
}
