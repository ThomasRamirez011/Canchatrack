package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Puerto de salida: evaluación y ascenso del jugador. Todas las escrituras exigen una transacción activa. */
public interface PlacementStore {

    /** Bloquea el expediente hasta el fin de la transacción. */
    Optional<PlayerRecord> lockPlayer(UUID playerId);

    Optional<UUID> currentCategoryId(UUID playerId);

    List<UUID> currentPanel(UUID playerId);

    /** @return {@code false} si el expediente ya no estaba en el estado {@code from} */
    boolean transition(UUID playerId, PlayerStatus from, PlayerStatus to);

    /** Cierra solo la asignación de categoría vigente; el panel de evaluadores se conserva. */
    void closeCurrentCategoryAssignment(UUID playerId);

    void insertPromotionAssignment(UUID playerId, UUID categoryId, UUID headCoachId, UUID roundId, UUID createdBy);
}
