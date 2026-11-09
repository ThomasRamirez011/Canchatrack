package co.edu.unipamplona.canchatrack.attendance.application;

import java.util.List;
import java.util.Map;

import co.edu.unipamplona.canchatrack.attendance.domain.FieldError;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/** 422 VALIDATION_FAILED con todos los errores acumulados ({@code errors:[{field,message}]}). */
final class ValidationFailure {

    private ValidationFailure() {
    }

    static void throwIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw of(errors);
        }
    }

    static DomainException of(List<FieldError> errors) {
        List<Map<String, String>> body = errors.stream()
                .map(e -> Map.of("field", e.field(), "message", e.message()))
                .toList();
        return new DomainException(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", body));
    }

    static DomainException of(String field, String message) {
        return of(List.of(new FieldError(field, message)));
    }
}
