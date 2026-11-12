package co.edu.unipamplona.canchatrack.scheduling.application;

import java.net.InetAddress;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.scheduling.api.MatchEventScheduling;
import co.edu.unipamplona.canchatrack.scheduling.api.MatchEventSlot;
import co.edu.unipamplona.canchatrack.scheduling.domain.SchedulingRules;

/**
 * Agenda de partidos para {@code competitions}: reutiliza la programación y la cancelación de eventos (cruces RN-14,
 * auditoría) cambiando solo el conjunto de tipos admitido. Siempre dentro de la transacción del llamador.
 */
@Service
public class MatchEventService implements MatchEventScheduling {

    private final SchedulingService scheduling;
    private final SchedulingStore store;

    public MatchEventService(SchedulingService scheduling, SchedulingStore store) {
        this.scheduling = scheduling;
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID schedule(MatchEventSlot slot, UUID actorId, InetAddress ip, String userAgent) {
        ScheduleEventCommand command = new ScheduleEventCommand(slot.eventType(), slot.categoryId(), slot.venueId(),
                slot.headCoachId(), slot.startAt(), slot.endAt(), slot.meetingAt(), slot.meetingPoint(), slot.instructions());
        return scheduling.scheduleOfTypes(new SchedulingActor(actorId, ip, userAgent), command, SchedulingRules.MATCH_EVENT_TYPES).id();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(UUID eventId, String reason, UUID actorId, InetAddress ip, String userAgent) {
        scheduling.cancelEvent(new SchedulingActor(actorId, ip, userAgent), eventId, reason);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID eventId) {
        store.complete(eventId);
    }
}
