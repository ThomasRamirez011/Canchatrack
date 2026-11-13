package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.documents.api.StoredFileDirectory;
import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.identity.api.StaffDirectory;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Dobles en memoria del puerto del comité y de las fachadas de identity y documents que usa. */
final class CommitteeFakes {

    private CommitteeFakes() {
    }

    static final class Minute {
        final UUID id = UUID.randomUUID();
        final UUID playerId;
        final MinuteData data;
        final String decision;
        final String reason;

        Minute(UUID playerId, MinuteData data, String decision, String reason) {
            this.playerId = playerId;
            this.data = data;
            this.decision = decision;
            this.reason = reason;
        }
    }

    static final class Assignment {
        final UUID playerId;
        final UUID categoryId;
        final UUID headCoachId;
        final UUID minuteId;
        final String capacityExceptionReason;
        boolean current = true;

        Assignment(UUID playerId, UUID categoryId, UUID headCoachId, UUID minuteId, String capacityExceptionReason) {
            this.playerId = playerId;
            this.categoryId = categoryId;
            this.headCoachId = headCoachId;
            this.minuteId = minuteId;
            this.capacityExceptionReason = capacityExceptionReason;
        }
    }

    static final class InMemoryCommittee implements CommitteeStore {

        final Map<UUID, PlayerStatus> players = new LinkedHashMap<>();
        final List<Minute> minutes = new ArrayList<>();
        final List<Assignment> assignments = new ArrayList<>();
        /** playerId -> evaluadores vigentes. */
        final Map<UUID, List<UUID>> panels = new HashMap<>();
        final Map<UUID, String> categoryNames = new HashMap<>();
        final Map<UUID, String> playerNames = new HashMap<>();
        LocalDate today = LocalDate.of(2026, 11, 8);

        UUID addPlayer(PlayerStatus status) {
            UUID id = UUID.randomUUID();
            players.put(id, status);
            return id;
        }

        long currentIn(UUID categoryId) {
            return assignments.stream().filter(a -> a.current && a.categoryId.equals(categoryId)
                    && players.get(a.playerId) != PlayerStatus.REJECTED
                    && players.get(a.playerId) != PlayerStatus.WITHDRAWN
                    && players.get(a.playerId) != PlayerStatus.SEASON_CLOSED).count();
        }

        long currentAssignments(UUID playerId) {
            return assignments.stream().filter(a -> a.current && a.playerId.equals(playerId)).count();
        }

        /** Siembra una asignación vigente previa, como la que dejaría un proceso anterior del mismo jugador. */
        void seedCurrent(UUID playerId, UUID categoryId, List<UUID> evaluators) {
            Minute minute = new Minute(playerId, new MinuteData("PREV", LocalDate.of(2026, 1, 1), List.of("x"), UUID.randomUUID()),
                    "ASSIGNED", null);
            minutes.add(minute);
            assignments.add(new Assignment(playerId, categoryId, UUID.randomUUID(), minute.id, null));
            panels.put(playerId, new ArrayList<>(evaluators));
        }

        @Override
        public Optional<PlayerRecord> lockPlayer(UUID playerId) {
            PlayerStatus status = players.get(playerId);
            return status == null ? Optional.empty() : Optional.of(new PlayerRecord(playerId, "JG-1", status));
        }

        @Override
        public LocalDate today(ZoneId zone) {
            return today;
        }

        @Override
        public void closeCurrentAssignment(UUID playerId) {
            assignments.stream().filter(a -> a.playerId.equals(playerId)).forEach(a -> a.current = false);
            panels.remove(playerId);
        }

        @Override
        public UUID insertMinute(UUID playerId, MinuteData minute, String decision, String reason, UUID createdBy) {
            boolean duplicate = minutes.stream().anyMatch(m -> m.playerId.equals(playerId)
                    && m.data.minuteNumber().equals(minute.minuteNumber()));
            if (duplicate) {
                throw new DuplicateMinuteException();
            }
            Minute row = new Minute(playerId, minute, decision, reason);
            minutes.add(row);
            return row.id;
        }

        @Override
        public void insertCategoryAssignment(UUID playerId, UUID categoryId, UUID headCoachId, UUID minuteId,
                                             String capacityExceptionReason, UUID createdBy) {
            assignments.add(new Assignment(playerId, categoryId, headCoachId, minuteId, capacityExceptionReason));
        }

        @Override
        public void insertEvaluatorAssignment(UUID playerId, UUID evaluatorId, UUID minuteId, UUID createdBy) {
            panels.computeIfAbsent(playerId, id -> new ArrayList<>()).add(evaluatorId);
        }

        @Override
        public boolean decide(UUID playerId, PlayerStatus target) {
            PlayerStatus status = players.get(playerId);
            if (status != PlayerStatus.SUBMITTED && status != PlayerStatus.COMMITTEE_PENDING) {
                return false;
            }
            players.put(playerId, target);
            return true;
        }

        @Override
        public String playerName(UUID playerId) {
            return playerNames.getOrDefault(playerId, "Jugador de prueba");
        }

        @Override
        public Optional<AssignmentView> findCurrentAssignment(UUID playerId) {
            return assignments.stream().filter(a -> a.current && a.playerId.equals(playerId)).findFirst().map(a -> {
                Minute minute = minutes.stream().filter(m -> m.id.equals(a.minuteId)).findFirst().orElseThrow();
                return new AssignmentView(playerId, players.get(playerId), a.categoryId,
                        categoryNames.getOrDefault(a.categoryId, "Categoría"), a.headCoachId,
                        panels.getOrDefault(playerId, List.of()), minute.data.minuteNumber(), minute.data.meetingDate(),
                        minute.data.minuteFileId(), a.capacityExceptionReason, Fakes.NOW);
            });
        }
    }

    /** Categorías cuyo cupo ocupado suma las asignaciones vigentes del comité (como hace la consulta real). */
    static final class CommitteeAwareCategories extends CallAdminFakes.InMemoryCategories {

        private final InMemoryCommittee committee;

        CommitteeAwareCategories(InMemoryCommittee committee) {
            this.committee = committee;
        }

        @Override
        public Optional<CategoryInfo> findByIdForUpdate(UUID categoryId) {
            return findById(categoryId).map(c -> new CategoryInfo(c.id(), c.name(), c.birthYearMin(), c.birthYearMax(),
                    c.capacity(), c.occupied() + (int) committee.currentIn(categoryId), c.nextCategoryId(), c.active()));
        }
    }

    record Enqueued(String type, Set<UUID> recipients, String subject, Map<String, String> payload,
                    String relatedEntityType, UUID relatedEntityId, String dedupScope) {
    }

    static final class RecordingQueue implements NotificationQueue {
        final List<Enqueued> sent = new ArrayList<>();

        @Override
        public void enqueue(String type, Collection<UUID> recipientUserIds, String subject, Map<String, String> payload,
                            String relatedEntityType, UUID relatedEntityId, String dedupScope) {
            sent.add(new Enqueued(type, new java.util.LinkedHashSet<>(recipientUserIds), subject, Map.copyOf(payload),
                    relatedEntityType, relatedEntityId, dedupScope));
        }
    }

    static final class FakeGuardians implements GuardianContacts {
        final Map<UUID, Set<UUID>> byPlayer = new HashMap<>();

        @Override
        public Set<UUID> guardianUserIdsOf(Collection<UUID> playerIds) {
            Set<UUID> users = new java.util.LinkedHashSet<>();
            for (UUID player : playerIds) {
                users.addAll(byPlayer.getOrDefault(player, Set.of()));
            }
            return users;
        }
    }

    static final class FakeStaff implements StaffDirectory {

        final Map<UUID, Set<String>> roles = new HashMap<>();

        UUID add(String... roleCodes) {
            UUID id = UUID.randomUUID();
            roles.put(id, new HashSet<>(List.of(roleCodes)));
            return id;
        }

        @Override
        public Map<UUID, Set<String>> activeRolesOf(Collection<UUID> userIds) {
            Map<UUID, Set<String>> result = new HashMap<>();
            for (UUID id : userIds) {
                if (roles.containsKey(id)) {
                    result.put(id, roles.get(id));
                }
            }
            return result;
        }
    }

    static final class FakeFiles implements StoredFileDirectory {

        final Set<UUID> known = new HashSet<>();

        UUID add() {
            UUID id = UUID.randomUUID();
            known.add(id);
            return id;
        }

        @Override
        public boolean exists(UUID fileId) {
            return known.contains(fileId);
        }
    }
}
