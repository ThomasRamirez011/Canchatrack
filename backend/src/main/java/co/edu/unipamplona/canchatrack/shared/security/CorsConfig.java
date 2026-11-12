package co.edu.unipamplona.canchatrack.shared.security;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;

/**
 * Política CORS de la API. Solo los orígenes de la lista blanca pueden leer respuestas desde un navegador; sin
 * credenciales de navegador (la autenticación es el token en {@code Authorization}, nunca una cookie), de modo que
 * {@code allowCredentials} queda desactivado. La cadena de seguridad la aplica antes de autenticar, para que las
 * solicitudes de verificación previa (OPTIONS) no exijan token y los errores 401/403 también sean legibles por el front.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
class CorsConfig {

    static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    static final List<String> REQUEST_HEADERS =
            List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key", "X-Correlation-Id");
    static final List<String> EXPOSED_HEADERS =
            List.of("Content-Disposition", "X-Correlation-Id", "Retry-After", "Location");
    static final long MAX_AGE_SECONDS = 3600;

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!properties.allowedOrigins().isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(properties.allowedOrigins());
            cors.setAllowedMethods(METHODS);
            cors.setAllowedHeaders(REQUEST_HEADERS);
            cors.setExposedHeaders(EXPOSED_HEADERS);
            cors.setAllowCredentials(false);
            cors.setMaxAge(MAX_AGE_SECONDS);
            source.registerCorsConfiguration(ApiPaths.V1 + "/**", cors);
        }
        return source;
    }
}
