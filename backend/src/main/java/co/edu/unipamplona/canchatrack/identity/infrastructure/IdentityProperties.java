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
 * @param requireVerifiedEmail si es verdadero, una cuenta con el correo sin confirmar no puede iniciar sesión
 * @param recovery            enlaces de restablecimiento y confirmación, y retención de tokens
 * @param rateLimit           límite de tasa de los puntos de entrada públicos
 * @param mfa                 segundo factor (TOTP) para los roles que lo exigen
 * @param bootstrap           credenciales de la primera cuenta (coordinador); vacías = no se crea nada
 */
@ConfigurationProperties(prefix = "canchatrack.identity")
public record IdentityProperties(
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue("7d") Duration refreshTtl,
        @DefaultValue("30d") Duration refreshAbsoluteTtl,
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("15m") Duration lockDuration,
        @DefaultValue("true") boolean requireVerifiedEmail,
        @DefaultValue Recovery recovery,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Mfa mfa,
        @DefaultValue Bootstrap bootstrap) {

    /**
     * @param resetTtl        vigencia del enlace de restablecimiento
     * @param verificationTtl vigencia del enlace de confirmación de correo
     * @param resetPath       ruta del front que recibe el token de restablecimiento
     * @param verifyPath      ruta del front que recibe el token de confirmación
     * @param retention       cuánto se guardan los tokens ya inservibles antes de la limpieza
     */
    public record Recovery(
            @DefaultValue("30m") Duration resetTtl,
            @DefaultValue("48h") Duration verificationTtl,
            @DefaultValue("/reset-password") String resetPath,
            @DefaultValue("/verify-email") String verifyPath,
            @DefaultValue("7d") Duration retention) {
    }

    /** Topes por ventana; cada tope aplica a una IP o a un correo (ver {@code RateLimitBucket}). */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30") int loginPerIp,
            @DefaultValue("15m") Duration loginWindow,
            @DefaultValue("10") int registerPerIp,
            @DefaultValue("1h") Duration registerWindow,
            @DefaultValue("10") int recoveryPerIp,
            @DefaultValue("3") int recoveryPerEmail,
            @DefaultValue("1h") Duration recoveryWindow,
            @DefaultValue("30") int confirmPerIp,
            @DefaultValue("15m") Duration confirmWindow) {
    }

    /**
     * @param enabled         si es falso nadie debe segundo factor (desarrollo y pruebas)
     * @param requiredRoles   roles que lo deben tener (por defecto coordinador y comité)
     * @param issuer          nombre que muestra la aplicación autenticadora
     * @param challengeTtl    vigencia del desafío entre la contraseña y el código
     * @param window          intervalos de 30 s tolerados a cada lado del actual (reloj del teléfono)
     * @param backupCodes     cuántos códigos de respaldo se entregan
     * @param encryptionKey   llave maestra de 32 bytes en Base64 que cifra los secretos; obligatoria si {@code enabled}
     */
    public record Mfa(
            @DefaultValue("true") boolean enabled,
            @DefaultValue({"COORDINATOR", "COMMITTEE"}) java.util.List<String> requiredRoles,
            @DefaultValue("CanchaTrack") String issuer,
            @DefaultValue("5m") Duration challengeTtl,
            @DefaultValue("1") int window,
            @DefaultValue("10") int backupCodes,
            String encryptionKey) {

        @Override
        public String toString() {
            return "Mfa[enabled=" + enabled + ", requiredRoles=" + requiredRoles + ", encryptionKey=***]";
        }
    }

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
