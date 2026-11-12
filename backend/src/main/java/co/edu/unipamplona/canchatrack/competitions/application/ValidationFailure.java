package co.edu.unipamplona.canchatrack.competitions.application;

import java.util.List;
import java.util.Map;

import co.edu.unipamplona.canchatrack.competitions.domain.FieldError;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/** 422 con todos los errores acumulados ({@code errors:[{field,message}]}). */
final class ValidationFailure {

    private ValidationFailure() {
    }

    static void throwIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", errors.stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message())).toList()));
        }
    }

    static DomainException of(String field, String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, null,
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
