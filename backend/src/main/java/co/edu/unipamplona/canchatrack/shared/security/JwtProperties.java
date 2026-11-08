package co.edu.unipamplona.canchatrack.shared.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del access token (JWT firmado con HS256).
 *
 * @param secret    clave en Base64, de al menos 32 bytes (256 bits). Siempre por variable de entorno (JWT_SECRET) salvo
 *                  el valor desechable del perfil dev; en producción no hay valor por defecto y la aplicación no arranca sin ella
 * @param issuer    emisor ({@code iss}) que se exige al validar
 * @param accessTtl vigencia del access token; corta a propósito porque no se puede revocar antes de vencer
 */
@ConfigurationProperties(prefix = "canchatrack.security.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("canchatrack") String issuer,
        @DefaultValue("15m") Duration accessTtl) {

    @Override
    public String toString() {
        return "JwtProperties[secret=***, issuer=" + issuer + ", accessTtl=" + accessTtl + "]";
    }
}
