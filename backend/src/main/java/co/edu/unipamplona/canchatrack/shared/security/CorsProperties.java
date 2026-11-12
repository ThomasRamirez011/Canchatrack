package co.edu.unipamplona.canchatrack.shared.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Orígenes del navegador autorizados a llamar a la API (el front). Vacío por defecto: sin orígenes no se emite
 * ninguna cabecera CORS y los navegadores bloquean las llamadas entre orígenes. Valores separados por coma, por
 * ejemplo {@code CORS_ALLOWED_ORIGINS=https://app.club.example}.
 *
 * @param allowedOrigins lista blanca exacta de orígenes ({@code esquema://host[:puerto]}), sin comodines
 */
@ConfigurationProperties(prefix = "canchatrack.security.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().map(String::trim).toList();
        List<String> problems = CorsOrigins.violations(allowedOrigins);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "canchatrack.security.cors.allowed-origins no es válido: " + String.join("; ", problems));
        }
    }
}
