package co.edu.unipamplona.canchatrack.evaluations.application;

import java.util.List;
import java.util.Map;

import co.edu.unipamplona.canchatrack.evaluations.domain.FieldError;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/** 422 con todos los errores acumulados ({@code errors:[{field,message}]}). */
final class ValidationFailure {

    private ValidationFailure() {
    }

    static void throwIfAny(List<FieldError> errors) {
        throwIfAny(ErrorCode.VALIDATION_FAILED, errors);
    }

    static void throwIfAny(ErrorCode code, List<FieldError> errors) {
        if (!errors.isEmpty()) {
            List<Map<String, String>> body = errors.stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
            throw new DomainException(code, null, Map.of("errors", body));
        }
    }
}
