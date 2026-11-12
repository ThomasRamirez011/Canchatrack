package co.edu.unipamplona.canchatrack.scheduling.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.scheduling.api.EventDirectory;
import co.edu.unipamplona.canchatrack.scheduling.api.EventInfo;

@Service
@Transactional(readOnly = true)
public class EventDirectoryService implements EventDirectory {

    private final SchedulingStore store;

    public EventDirectoryService(SchedulingStore store) {
        this.store = store;
    }

    @Override
    public Optional<EventInfo> find(UUID eventId) {
        return store.findEvent(eventId).map(e -> new EventInfo(e.id(), e.eventType(), e.categoryId(), e.venueId(),
                e.startAt(), e.endAt(), e.status(), e.headCoachId()));
    }
}
