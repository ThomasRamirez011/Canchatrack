package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo identity ({@code canchatrack.identity.*}).
 *
 * @param bcryptStrength      costo de BCrypt (12 ≈ 250 ms por verificación; solo se baja en pruebas)
 * @param refreshTtl          vigencia de cada refresh token
 * @param refreshAbsoluteTtl  tope de la sesión completa
 * @param maxFailedAttempts   intentos fallidos consecutivos antes de bloquear la cuenta
 * @param lockDuration        duración del bloqueo temporal
 * @param bootstrap           credenciales de la primera cuenta (coordinador); vacías = no se crea nada
 */
@ConfigurationProperties(prefix = "canchatrack.identity")
public record IdentityProperties(
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue("7d") Duration refreshTtl,
        @DefaultValue("30d") Duration refreshAbsoluteTtl,
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("15m") Duration lockDuration,
        @DefaultValue Bootstrap bootstrap) {

    /** Primera cuenta del sistema. La contraseña nunca se registra en logs. */
    public record Bootstrap(String coordinatorEmail, String coordinatorName, String coordinatorPassword) {

        @Override
        public String toString() {
            return "Bootstrap[coordinatorEmail=" + coordinatorEmail + ", coordinatorPassword=***]";
        }

        boolean configured() {
            return coordinatorEmail != null && !coordinatorEmail.isBlank()
                    && coordinatorPassword != null && !coordinatorPassword.isBlank();
        }
    }
}
