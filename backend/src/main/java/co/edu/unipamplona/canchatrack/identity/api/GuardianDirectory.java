package co.edu.unipamplona.canchatrack.identity.api;

import java.util.Optional;
import java.util.UUID;

/** Fachada pública: ficha de acudiente de una cuenta. Los demás módulos no consultan {@code guardians} por su cuenta. */
public interface GuardianDirectory {

    /** @return el id de la ficha de acudiente de la cuenta, o vacío si la cuenta no es de un acudiente */
    Optional<UUID> guardianIdOf(UUID userId);

    /** @return si existe una ficha de acudiente con ese id (para validar referencias desde otros módulos) */
    boolean exists(UUID guardianId);
}
