package co.edu.unipamplona.canchatrack.identity.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Fachada pública: cuentas de los acudientes de un jugador, para avisarles (RF-035). Quien notifica no consulta
 * {@code guardians} ni el vínculo por su cuenta.
 */
public interface GuardianContacts {

    /** Ids de cuenta de los acudientes con vínculo vigente con alguno de los jugadores indicados, sin repetir. */
    Set<UUID> guardianUserIdsOf(Collection<UUID> playerIds);
}
