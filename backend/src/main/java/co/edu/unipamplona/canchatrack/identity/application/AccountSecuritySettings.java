package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Duration;

/**
 * Parámetros de los enlaces de un solo uso y de la retención.
 *
 * @param resetTtl        vigencia del enlace de restablecimiento de contraseña
 * @param verificationTtl vigencia del enlace de confirmación de correo
 * @param appUrl          dirección pública del front; vacía = el correo lleva el código en lugar del enlace
 * @param resetPath       ruta del front que recibe el token de restablecimiento
 * @param verifyPath      ruta del front que recibe el token de confirmación
 * @param retention       cuánto se conservan los tokens ya inservibles antes de borrarlos
 */
public record AccountSecuritySettings(Duration resetTtl, Duration verificationTtl, String appUrl, String resetPath,
                                      String verifyPath, Duration retention) {

    public AccountSecuritySettings {
        requirePositive(resetTtl, "resetTtl");
        requirePositive(verificationTtl, "verificationTtl");
        requirePositive(retention, "retention");
        appUrl = appUrl == null ? "" : appUrl.trim().replaceAll("/+$", "");
        resetPath = path(resetPath, "resetPath");
        verifyPath = path(verifyPath, "verifyPath");
    }

    /** Enlace de restablecimiento, o cadena vacía si no hay dirección del front configurada. */
    public String resetUrl(String rawToken) {
        return appUrl.isEmpty() ? "" : appUrl + resetPath + "?token=" + rawToken;
    }

    /** Enlace de confirmación de correo, o cadena vacía si no hay dirección del front configurada. */
    public String verifyUrl(String rawToken) {
        return appUrl.isEmpty() ? "" : appUrl + verifyPath + "?token=" + rawToken;
    }

    private static String path(String value, String name) {
        if (value == null || !value.matches("/[A-Za-z0-9/_-]*")) {
            throw new IllegalArgumentException(name + " must start with / and contain only letters, digits, / _ -");
        }
        return value;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
