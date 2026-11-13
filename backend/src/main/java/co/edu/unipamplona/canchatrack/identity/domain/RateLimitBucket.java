package co.edu.unipamplona.canchatrack.identity.domain;

/**
 * Qué se cuenta en el límite de tasa. Cada cubeta tiene su propio tope y ventana; el sujeto (IP o correo) se guarda
 * solo como huella.
 */
public enum RateLimitBucket {
    /** Inicios de sesión desde una misma IP. */
    LOGIN_IP,
    /** Altas del registro público de acudientes desde una misma IP. */
    REGISTER_IP,
    /** Solicitudes de correos de recuperación o verificación desde una misma IP. */
    RECOVERY_IP,
    /** Solicitudes de correos de recuperación o verificación para un mismo correo (evita inundar a una víctima). */
    RECOVERY_EMAIL,
    /** Canjes de enlaces (restablecer contraseña, confirmar correo) desde una misma IP. */
    CONFIRM_IP
}
