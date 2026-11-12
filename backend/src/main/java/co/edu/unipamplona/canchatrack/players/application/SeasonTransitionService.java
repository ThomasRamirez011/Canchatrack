package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.players.api.SeasonClosureGateway;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Cierre y reapertura del expediente (T-11, T-12). El expediente se bloquea y el cambio se condiciona al estado de
 * origen, así que dos solicitudes simultáneas sobre el mismo jugador se serializan y la segunda recibe un 409 limpio.
 * La categoría vigente no se toca: el cierre no borra nada y reabrir devuelve al jugador a su lugar (RN-18, RN-20).
 */
@Service
public class SeasonTransitionService implements SeasonClosureGateway {

    static final String CLOSE = "T-11";
    static final String REOPEN = "T-12";

    private final PlacementStore store;

    public SeasonTransitionService(PlacementStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public void closeSeason(UUID playerId, UUID actorId) {
        PlayerRecord player = lock(playerId);
        if (player.status() != PlayerStatus.ACTIVE) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "Solo un jugador activo puede cerrar la temporada (estado actual: " + player.status()
                            + "). Un jugador en evaluación o con un ascenso aprobado debe resolverlo antes.");
        }
        change(playerId, CLOSE, PlayerStatus.ACTIVE, PlayerStatus.SEASON_CLOSED, null, actorId);
    }

    @Override
    @Transactional
    public void reopenSeason(UUID playerId, UUID actorId, String reason) {
        PlayerRecord player = lock(playerId);
        if (player.status() != PlayerStatus.SEASON_CLOSED) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "Solo se puede reabrir un expediente con la temporada cerrada (estado actual: " + player.status() + ").");
        }
        change(playerId, REOPEN, PlayerStatus.SEASON_CLOSED, PlayerStatus.ACTIVE, reason, actorId);
    }

    private void change(UUID playerId, String code, PlayerStatus from, PlayerStatus to, String reason, UUID actorId) {
        if (!store.transition(playerId, from, to)) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT, "El estado del expediente cambió; vuelva a consultarlo.");
        }
        store.recordStateChange(playerId, code, from, to, reason, actorId);
    }

    private PlayerRecord lock(UUID playerId) {
        return store.lockPlayer(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
    }
}
