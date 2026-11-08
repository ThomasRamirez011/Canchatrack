package co.edu.unipamplona.canchatrack.shared.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Traduce toda excepción a {@code application/problem+json} con {@code code} estable y {@code correlationId}
 * (DDS 8.1 y secc. 13). Nunca expone trazas, SQL ni mensajes internos.
 *
 * <p>Los errores generados por el propio framework (JSON malformado, método no permitido, validación de
 * argumentos, etc.) conservan su estado HTTP y se enriquecen con {@code correlationId} y un {@code code}
 * genérico {@code HTTP_<estado>}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_PREFIX = "urn:canchatrack:problem:";

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        ProblemDetail problem = problem(ex.getCode(), ex.getMessage());
        ex.getExtensions().forEach(problem::setProperty);
        return ResponseEntity.status(ex.getCode().httpStatus()).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        // Mismo mensaje para "no existe" y "no es tuyo": evita filtrar datos entre acudientes (PA-14).
        return respond(ErrorCode.AUTH_FORBIDDEN, ErrorCode.AUTH_FORBIDDEN.defaultDetail());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return respond(ErrorCode.CONCURRENT_MODIFICATION, ErrorCode.CONCURRENT_MODIFICATION.defaultDetail());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        LOG.error("Unhandled exception [correlationId={}]", CorrelationIds.current(), ex);
        return respond(ErrorCode.INTERNAL_ERROR,
                "Ocurrió un error interno. Informe el correlationId al soporte.");
    }

    /** Validación de cuerpos ({@code @Valid}): 422 {@code VALIDATION_FAILED} con la lista de campos inválidos. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", fieldError.getDefaultMessage() == null ? "Valor no válido." : fieldError.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultDetail());
        problem.setProperty("errors", errors);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus()).body(problem);
    }

    /** Punto único por donde pasan los errores del propio framework: los enriquece sin cambiar su estado HTTP. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            Map<String, Object> properties = problem.getProperties();
            if (properties == null || !properties.containsKey("code")) {
                problem.setProperty("code", "HTTP_" + statusCode.value());
            }
            problem.setProperty("correlationId", CorrelationIds.current());
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail) {
        return ResponseEntity.status(code.httpStatus()).body(problem(code, detail));
    }

    private static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.httpStatus()), detail);
        problem.setType(URI.create(TYPE_PREFIX + code.name()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setProperty("correlationId", CorrelationIds.current());
        return problem;
    }
}
