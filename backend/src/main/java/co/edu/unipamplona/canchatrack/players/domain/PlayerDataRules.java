package co.edu.unipamplona.canchatrack.players.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentNumber;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

/**
 * Reglas de forma de los datos de un jugador. Los rangos de edad por categoría están pendientes del cliente (DP-01),
 * así que solo se rechazan fechas imposibles: futuras o de hace más de {@link #MAX_AGE_YEARS} años.
 */
public final class PlayerDataRules {

    static final int MAX_AGE_YEARS = 100;
    static final int MAX_NAME = 100;
    private static final Pattern NAME = Pattern.compile("\\p{L}[\\p{L} .'-]*");

    private PlayerDataRules() {
    }

    public static List<FieldError> violations(String firstName, String lastName, String documentType,
                                              String documentNumber, LocalDate birthDate, LocalDate today) {
        List<FieldError> errors = new ArrayList<>();
        checkName("player.firstName", firstName, "El nombre", errors);
        checkName("player.lastName", lastName, "El apellido", errors);
        if (IdDocumentType.parse(documentType).isEmpty()) {
            errors.add(new FieldError("player.documentType", "El tipo de documento debe ser CC, CE, PA, TI o RC."));
        }
        if (IdDocumentNumber.normalize(documentNumber).isEmpty()) {
            errors.add(new FieldError("player.documentNumber",
                    "El número de documento debe tener entre 4 y 30 letras, dígitos o guiones."));
        }
        if (birthDate == null) {
            errors.add(new FieldError("player.birthDate", "La fecha de nacimiento es obligatoria."));
        } else if (birthDate.isAfter(today)) {
            errors.add(new FieldError("player.birthDate", "La fecha de nacimiento no puede ser futura."));
        } else if (birthDate.isBefore(today.minusYears(MAX_AGE_YEARS))) {
            errors.add(new FieldError("player.birthDate", "La fecha de nacimiento no es válida."));
        }
        return errors;
    }

    private static void checkName(String field, String value, String label, List<FieldError> errors) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            errors.add(new FieldError(field, label + " es obligatorio."));
        } else if (trimmed.length() > MAX_NAME || !NAME.matcher(trimmed).matches()) {
            errors.add(new FieldError(field, label + " contiene caracteres no válidos o es demasiado largo."));
        }
    }
}
