package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Puerto de salida de matrícula y activación (T-03, T-04). Las escrituras exigen transacción. */
public interface RegistrationStore {

    /** Bloquea la fila del jugador y devuelve su estado; vacío si no existe. */
    Optional<PlayerStatus> lockStatus(UUID playerId);

    /** Cambia el estado y avanza la versión optimista. Quien llama ya tiene la fila bloqueada. */
    void transition(UUID playerId, PlayerStatus to);

    List<UUID> activePlayerIds();
}
