package co.edu.unipamplona.canchatrack.players.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fachada pública: plantilla de una categoría. {@code attendance} la usa para saber a quién pasar lista sin consultar las
 * tablas de {@code players}.
 */
public interface CategoryRoster {

    /** Jugadores en actividad (ACTIVE o IN_EVALUATION) con asignación vigente en la categoría. */
    List<UUID> activePlayerIds(UUID categoryId);

    /** Datos de identificación de los jugadores indicados; los inexistentes no aparecen. */
    Map<UUID, RosterPlayer> describe(Collection<UUID> playerIds);
}
