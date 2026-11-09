package co.edu.unipamplona.canchatrack.players.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.players.api.CategoryRoster;
import co.edu.unipamplona.canchatrack.players.api.RosterPlayer;

@Service
@Transactional(readOnly = true)
public class RosterService implements CategoryRoster {

    private final RosterStore store;

    public RosterService(RosterStore store) {
        this.store = store;
    }

    @Override
    public List<UUID> activePlayerIds(UUID categoryId) {
        return store.activePlayerIds(categoryId);
    }

    @Override
    public Map<UUID, RosterPlayer> describe(Collection<UUID> playerIds) {
        Map<UUID, RosterPlayer> result = new LinkedHashMap<>();
        if (!playerIds.isEmpty()) {
            store.findPlayers(playerIds).forEach(p -> result.put(p.id(), p));
        }
        return result;
    }
}
