package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.players.api.CategoryRoster;
import co.edu.unipamplona.canchatrack.players.api.RosterPlayer;

/** Dobles en memoria para probar los avisos de agenda sin base de datos. */
final class AgendaFakes {

    static final Instant NOW = Instant.parse("2026-11-30T20:00:00Z");

    private AgendaFakes() {
    }

    record Enqueued(String type, Set<UUID> recipients, String subject, Map<String, String> payload,
                    String relatedEntityType, UUID relatedEntityId, String dedupScope) {
    }

    static final class RecordingQueue implements NotificationQueue {
        final List<Enqueued> sent = new ArrayList<>();

        @Override
        public void enqueue(String type, Collection<UUID> recipientUserIds, String subject, Map<String, String> payload,
                            String relatedEntityType, UUID relatedEntityId, String dedupScope) {
            sent.add(new Enqueued(type, new LinkedHashSet<>(recipientUserIds), subject, Map.copyOf(payload),
                    relatedEntityType, relatedEntityId, dedupScope));
        }
    }

    static final class FakeRoster implements CategoryRoster {
        final Map<UUID, List<UUID>> byCategory = new HashMap<>();

        @Override
        public List<UUID> activePlayerIds(UUID categoryId) {
            return byCategory.getOrDefault(categoryId, List.of());
        }

        @Override
        public Map<UUID, RosterPlayer> describe(Collection<UUID> playerIds) {
            throw new UnsupportedOperationException();
        }
    }

    static final class FakeGuardians implements GuardianContacts {
        final Map<UUID, UUID> guardianByPlayer = new HashMap<>();

        @Override
        public Set<UUID> guardianUserIdsOf(Collection<UUID> playerIds) {
            Set<UUID> users = new LinkedHashSet<>();
            for (UUID player : playerIds) {
                UUID guardian = guardianByPlayer.get(player);
                if (guardian != null) {
                    users.add(guardian);
                }
            }
            return users;
        }
    }

    static final class FakeStore implements SchedulingStore {
        EventContext context = new EventContext("Sub-12", "Calle 5 # 3-20", "Carlos Ruiz");
        List<ReminderCandidate> upcoming = new ArrayList<>();
        Instant lastFrom;
        Instant lastTo;

        @Override
        public Instant now() {
            return NOW;
        }

        @Override
        public EventContext contextOf(EventView event) {
            return context;
        }

        @Override
        public List<ReminderCandidate> findUpcomingForReminders(Instant from, Instant to) {
            lastFrom = from;
            lastTo = to;
            return upcoming.stream().filter(c -> c.event().startAt().isAfter(from) && !c.event().startAt().isAfter(to)).toList();
        }

        @Override
        public VenueInfo insertVenue(String name, String address, String reference, UUID createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<VenueInfo> findVenues(boolean includeInactive) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VenueInfo> findVenue(UUID venueId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lockSlots(UUID venueId, UUID coachId, UUID categoryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ConflictView> findConflicts(UUID venueId, UUID coachId, UUID categoryId, Instant startAt, Instant endAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UUID insertEvent(String eventType, UUID categoryId, UUID venueId, Instant startAt, Instant endAt,
                                Instant meetingAt, String meetingPoint, String instructions, UUID headCoachId, UUID createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EventView> findEvent(UUID eventId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> lockStatus(UUID eventId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(UUID eventId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(UUID eventId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EventView> findEvents(UUID categoryId, Instant from, Instant to, UUID coachId, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    static EventView training(UUID categoryId, UUID coachId, Instant startAt) {
        return new EventView(UUID.randomUUID(), "TRAINING", categoryId, UUID.randomUUID(), "Cancha Municipal", coachId,
                startAt, startAt.plusSeconds(5400), null, null, "Traer uniforme", "CONFIRMED", null);
    }
}
