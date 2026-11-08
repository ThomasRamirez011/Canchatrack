package co.edu.unipamplona.canchatrack.shared.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asigna un {@code correlationId} a cada solicitud: usa el de la cabecera {@code X-Correlation-Id} si tiene un
 * formato seguro y, si no, genera uno. Se publica en el MDC (logs), en la respuesta y en los errores.
 *
 * <p>Corre antes que la cadena de Spring Security para que también las respuestas 401/403 lo lleven.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Solo caracteres inocuos y longitud acotada: evita inyección en logs y cabeceras. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(CorrelationIds.HEADER);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        response.setHeader(CorrelationIds.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
