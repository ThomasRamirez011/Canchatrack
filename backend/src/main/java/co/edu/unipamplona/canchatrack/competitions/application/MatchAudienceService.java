package co.edu.unipamplona.canchatrack.competitions.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.scheduling.api.MatchAudience;

/**
 * A quién recuerda la agenda un partido: los acudientes de los jugadores con convocatoria confirmada. Los propuestos o no
 * elegibles todavía no saben si juegan, así que no reciben recordatorio.
 */
@Service
public class MatchAudienceService implements MatchAudience {

    private final CompetitionStore store;
    private final GuardianContacts guardianContacts;

    public MatchAudienceService(CompetitionStore store, GuardianContacts guardianContacts) {
        this.store = store;
        this.guardianContacts = guardianContacts;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> confirmedGuardianUserIds(UUID eventId) {
        List<UUID> players = store.confirmedPlayersOfEvent(eventId);
        return players.isEmpty() ? Set.of() : guardianContacts.guardianUserIdsOf(players);
    }
}
