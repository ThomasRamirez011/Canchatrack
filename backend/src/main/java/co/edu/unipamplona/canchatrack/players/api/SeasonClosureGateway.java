package co.edu.unipamplona.canchatrack.players.api;

import java.util.UUID;

/**
 * Fachada pública: cierre y reapertura del expediente por temporada (DAD E11, transiciones T-11 y T-12). La usa
 * {@code reports}, que decide si hay un informe que respalde el cierre; aquí solo vive el cambio de estado. Ambas
 * operaciones se unen a la transacción de quien llama, bloquean el expediente y dejan la transición en
 * {@code state_history}.
 */
public interface SeasonClosureGateway {

    /**
     * T-11: ACTIVE → SEASON_CLOSED. Un jugador en evaluación o con un ascenso pendiente debe resolverlo antes.
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException RESOURCE_NOT_FOUND o PLAYER_STATE_CONFLICT
     */
    void closeSeason(UUID playerId, UUID actorId);

    /**
     * T-12: SEASON_CLOSED → ACTIVE, con el motivo de la reapertura. Conserva la categoría vigente y todo el historial.
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException RESOURCE_NOT_FOUND o PLAYER_STATE_CONFLICT
     */
    void reopenSeason(UUID playerId, UUID actorId, String reason);
}
