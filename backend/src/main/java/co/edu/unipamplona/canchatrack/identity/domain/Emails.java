package co.edu.unipamplona.canchatrack.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Normalización y validación mínima de correos. La verificación real de propiedad llega con el flujo de notificaciones. */
public final class Emails {

    public static final int MAX_LENGTH = 254;

    private static final Pattern SIMPLE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {
    }

    /** Recorta y pasa a minúsculas; el índice único de la base es sobre {@code lower(email)}. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String normalizedEmail) {
        return normalizedEmail != null
                && normalizedEmail.length() <= MAX_LENGTH
                && SIMPLE.matcher(normalizedEmail).matches();
    }
}
