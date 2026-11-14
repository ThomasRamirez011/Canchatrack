package co.edu.unipamplona.canchatrack.shared.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.web.CorrelationIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 401 y 403 que se producen en la cadena de filtros (antes de llegar a un controlador) con el mismo formato
 * {@code application/problem+json} y los mismos códigos que el resto de la API. No se revela por qué falló el token.
 */
@Component
public class ProblemJsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String TYPE_PREFIX = "urn:canchatrack:problem:";

    private final ObjectMapper objectMapper;

    public ProblemJsonSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, ErrorCode.AUTH_UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, ErrorCode.AUTH_FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_PREFIX + code.name());
        body.put("title", code.title());
        body.put("status", code.httpStatus());
        body.put("detail", code.defaultDetail());
        body.put("code", code.name());
        body.put("correlationId", CorrelationIds.current());
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
