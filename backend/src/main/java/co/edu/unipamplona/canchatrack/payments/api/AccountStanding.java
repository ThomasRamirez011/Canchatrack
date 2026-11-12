package co.edu.unipamplona.canchatrack.payments.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Fachada pública: cumplimiento de la política de mora (RN-07). {@code payments} es el único que conoce cargos y pagos;
 * quien convoca solo pregunta quién está en mora, sin ver montos.
 */
public interface AccountStanding {

    /**
     * Jugadores (de los indicados) con algún cargo vigente vencido más allá de los días de gracia y todavía con saldo
     * pendiente en la fecha dada. Los cargos anulados o saldados no cuentan. Los días de gracia son parametrizables
     * ({@code canchatrack.payments.arrears-grace-days}); la política definitiva sigue pendiente del cliente (DP-05).
     */
    Set<UUID> inArrears(Collection<UUID> playerIds, LocalDate asOf);
}
