package co.edu.unipamplona.canchatrack.players.api;

import java.util.List;
import java.util.UUID;

/**
 * Fachada pública: lo que {@code evaluations} necesita del expediente del jugador (estado, panel y categoría). Todas
 * las operaciones se unen a la transacción de quien llama y bloquean el expediente, de modo que abrir, cerrar y ascender
 * sobre el mismo jugador se serializan.
 */
public interface EvaluationGateway {

    /**
     * Transición ACTIVE → IN_EVALUATION (DAD E07). Exige categoría vigente y un panel de exactamente tres cuentas
     * activas con rol admitido.
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException RESOURCE_NOT_FOUND, PLAYER_STATE_CONFLICT
     *                                                                       o PANEL_INVALID
     */
    EvaluationSubject startEvaluation(UUID playerId);

    /**
     * Cierra la evaluación (T-06 / T-07): IN_EVALUATION → PROMOTION_APPROVED si hubo unanimidad, o de vuelta a ACTIVE
     * con plan de seguimiento en caso contrario.
     */
    void concludeEvaluation(UUID playerId, boolean promotionApproved);

    /**
     * T-08: PROMOTION_APPROVED → ACTIVE en la nueva categoría, si hay cupo. Cierra la asignación anterior (no la borra)
     * y conserva el panel. Con {@code targetCategoryId} nulo usa la categoría siguiente de la actual.
     */
    PromotionResult promote(UUID playerId, UUID roundId, UUID targetCategoryId, UUID headCoachId, UUID actorId);

    /** Evaluadores vigentes del jugador. */
    List<UUID> panelOf(UUID playerId);
}
