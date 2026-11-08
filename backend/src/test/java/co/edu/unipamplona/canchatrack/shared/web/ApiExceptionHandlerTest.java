package co.edu.unipamplona.canchatrack.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @BeforeEach
    void setUp() {
        MDC.put(CorrelationIds.MDC_KEY, "test-correlation-id");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void domainExceptionKeepsStableCodeStatusAndExtensions() {
        DomainException ex = new DomainException(ErrorCode.CALLUP_NOT_ELIGIBLE,
                "El certificado médico está vencido.", Map.of("expiresOn", "2026-07-10"));

        ResponseEntity<ProblemDetail> response = handler.handleDomain(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getDetail()).isEqualTo("El certificado médico está vencido.");
        assertThat(problem.getProperties())
                .containsEntry("code", "CALLUP_NOT_ELIGIBLE")
                .containsEntry("correlationId", "test-correlation-id")
                .containsEntry("expiresOn", "2026-07-10");
    }

    @Test
    void accessDeniedBecomes403WithoutLeakingTheCause() {
        ResponseEntity<ProblemDetail> response =
                handler.handleAccessDenied(new AccessDeniedException("jugador 123 pertenece a otro acudiente"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().getDetail()).doesNotContain("123");
        assertThat(response.getBody().getProperties()).containsEntry("code", "AUTH_FORBIDDEN");
    }

    @Test
    void unexpectedErrorBecomes500WithoutInternalMessage() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpected(new IllegalStateException("password=secreto jdbc:postgresql://host"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getDetail()).doesNotContain("secreto").doesNotContain("jdbc");
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "INTERNAL_ERROR")
                .containsEntry("correlationId", "test-correlation-id");
    }

    @Test
    void blankDetailFallsBackToTheCatalogMessage() {
        DomainException ex = new DomainException(ErrorCode.CATEGORY_FULL, " ");

        assertThat(ex.getMessage()).isEqualTo(ErrorCode.CATEGORY_FULL.defaultDetail());
    }
}
