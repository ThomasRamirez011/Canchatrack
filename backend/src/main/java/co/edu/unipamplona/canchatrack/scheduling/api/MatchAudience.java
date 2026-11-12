package co.edu.unipamplona.canchatrack.scheduling.api;

import java.util.Set;
import java.util.UUID;

/**
 * Puerto que {@code competitions} implementa para que la agenda sepa a quién recordar un partido: las cuentas de los
 * acudientes de los jugadores con convocatoria <strong>confirmada</strong>. La agenda no conoce convocatorias; solo pregunta.
 */
public interface MatchAudience {

    /**
     * @param eventId evento de agenda del partido
     * @return cuentas de acudientes de los convocados confirmados; vacío si el evento no es un partido o aún no hay confirmados
     */
    Set<UUID> confirmedGuardianUserIds(UUID eventId);
}
