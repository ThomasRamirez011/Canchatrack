package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: temporadas. La escritura exige una transacción activa. */
public interface SeasonStore {

    /** @throws DuplicateSeasonNameException si el nombre ya existe */
    SeasonInfo insert(String name, Instant startAt, Instant endAt, UUID createdBy);

    List<SeasonInfo> findAll();

    Optional<SeasonInfo> findById(UUID seasonId);

    /** Bloquea la fila hasta el fin de la transacción para serializar transiciones de estado. */
    Optional<SeasonInfo> findByIdForUpdate(UUID seasonId);

    /** PLANNED → ACTIVE; {@code false} si la temporada no estaba planificada. */
    boolean activate(UUID seasonId);
}
