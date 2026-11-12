package co.edu.unipamplona.canchatrack.players.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.api.RosterPlayer;

/** Puerto de salida de solo lectura: plantilla por categoría. */
public interface RosterStore {

    List<UUID> activePlayerIds(UUID categoryId);

    List<RosterPlayer> findPlayers(Collection<UUID> playerIds);
}
