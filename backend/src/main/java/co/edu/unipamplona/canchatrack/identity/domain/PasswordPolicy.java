package co.edu.unipamplona.canchatrack.identity.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Política de contraseñas para cuentas propias. El máximo de 72 bytes es el límite real de BCrypt: más allá se
 * ignoraría en silencio, así que se rechaza.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** Devuelve los incumplimientos (vacío si la contraseña es aceptable). Nunca incluye la contraseña en los mensajes. */
    public static List<String> violations(String password, String email) {
        List<String> violations = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            violations.add("La contraseña debe tener al menos " + MIN_LENGTH + " caracteres.");
        }
        if (password != null) {
            if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                violations.add("La contraseña no puede superar " + MAX_BYTES + " bytes.");
            }
            boolean hasLetter = password.codePoints().anyMatch(Character::isLetter);
            boolean hasDigit = password.codePoints().anyMatch(Character::isDigit);
            if (!hasLetter || !hasDigit) {
                violations.add("La contraseña debe contener al menos una letra y un dígito.");
            }
            if (email != null && password.equalsIgnoreCase(email.trim())) {
                violations.add("La contraseña no puede ser igual al correo.");
            }
        }
        return List.copyOf(violations);
    }
}
