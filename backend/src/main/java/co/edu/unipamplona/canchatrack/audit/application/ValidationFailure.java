package co.edu.unipamplona.canchatrack.audit.application;

import java.util.List;
import java.util.Map;

import co.edu.unipamplona.canchatrack.audit.domain.FieldError;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/** 422 VALIDATION_FAILED con todos los errores acumulados ({@code errors:[{field,message}]}). */
final class ValidationFailure {

    private ValidationFailure() {
    }

    static void throwIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            List<Map<String, String>> body = errors.stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
            throw new DomainException(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", body));
        }
    }
}
