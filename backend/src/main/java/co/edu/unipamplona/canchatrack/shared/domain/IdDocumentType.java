package co.edu.unipamplona.canchatrack.shared.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Tipos de documento de identidad admitidos (provisional: los del contexto colombiano; el club puede ajustar la lista
 * con una migración y este enum). Lo comparten acudientes y jugadores, por eso vive en {@code shared}.
 */
public enum IdDocumentType {

    CC(true),  // cédula de ciudadanía
    CE(true),  // cédula de extranjería
    PA(true),  // pasaporte
    TI(false), // tarjeta de identidad
    RC(false); // registro civil

    private final boolean adult;

    IdDocumentType(boolean adult) {
        this.adult = adult;
    }

    /** Documentos que puede tener un acudiente (persona adulta). */
    public boolean isForAdults() {
        return adult;
    }

    public static Optional<IdDocumentType> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (IdDocumentType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
