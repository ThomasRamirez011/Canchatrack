package co.edu.unipamplona.canchatrack.shared.security;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Los navegadores exigen una respuesta explícita cuando una página pública (el front publicado) llama a un servidor en
 * la red local o en {@code localhost}: la verificación previa trae {@code Access-Control-Request-Private-Network: true} y
 * la respuesta debe llevar {@code Access-Control-Allow-Private-Network: true}. Este filtro solo añade esa cabecera; si el
 * origen está permitido lo decide {@code CorsConfig}, que sigue siendo quien admite o rechaza la solicitud.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class PrivateNetworkAccessFilter extends OncePerRequestFilter {

    static final String REQUEST_HEADER = "Access-Control-Request-Private-Network";
    static final String RESPONSE_HEADER = "Access-Control-Allow-Private-Network";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) && "true".equalsIgnoreCase(request.getHeader(REQUEST_HEADER))) {
            response.setHeader(RESPONSE_HEADER, "true");
        }
        chain.doFilter(request, response);
    }
}
