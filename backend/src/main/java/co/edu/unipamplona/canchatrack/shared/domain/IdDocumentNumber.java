package co.edu.unipamplona.canchatrack.shared.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Número de documento normalizado: sin espacios en los extremos, en mayúsculas, solo letras, dígitos y guion. */
public final class IdDocumentNumber {

    public static final int MAX_LENGTH = 30;
    private static final Pattern VALID = Pattern.compile("[A-Z0-9-]{4,30}");

    private IdDocumentNumber() {
    }

    /** @return el número normalizado, o vacío si no es válido (también si es {@code null}) */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return VALID.matcher(normalized).matches() ? Optional.of(normalized) : Optional.empty();
    }
}
