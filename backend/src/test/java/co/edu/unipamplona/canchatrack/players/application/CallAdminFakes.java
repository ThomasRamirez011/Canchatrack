package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.CallStatus;
import co.edu.unipamplona.canchatrack.players.domain.SeasonStatus;

/** Dobles en memoria de los puertos de administración de temporadas y convocatorias. */
final class CallAdminFakes {

    private CallAdminFakes() {
    }

    static final class InMemorySeasons implements SeasonStore {

        final Map<UUID, SeasonInfo> rows = new LinkedHashMap<>();

        SeasonInfo add(SeasonStatus status) {
            SeasonInfo season = new SeasonInfo(UUID.randomUUID(), "Temporada " + (rows.size() + 1),
                    Fakes.NOW.minusSeconds(86_400L * 30), Fakes.NOW.plusSeconds(86_400L * 300), status, Fakes.NOW);
            rows.put(season.id(), season);
            return season;
        }

        @Override
        public SeasonInfo insert(String name, Instant startAt, Instant endAt, UUID createdBy) {
            if (rows.values().stream().anyMatch(s -> s.name().equals(name))) {
                throw new DuplicateSeasonNameException();
            }
            SeasonInfo season = new SeasonInfo(UUID.randomUUID(), name, startAt, endAt, SeasonStatus.PLANNED, Fakes.NOW);
            rows.put(season.id(), season);
            return season;
        }

        @Override
        public List<SeasonInfo> findAll() {
            return rows.values().stream().sorted(Comparator.comparing(SeasonInfo::startAt).reversed()).toList();
        }

        @Override
        public Optional<SeasonInfo> findById(UUID seasonId) {
            return Optional.ofNullable(rows.get(seasonId));
        }

        @Override
        public Optional<SeasonInfo> findByIdForUpdate(UUID seasonId) {
            return findById(seasonId);
        }

        @Override
        public boolean activate(UUID seasonId) {
            SeasonInfo season = rows.get(seasonId);
            if (season == null || season.status() != SeasonStatus.PLANNED) {
                return false;
            }
            rows.put(seasonId, new SeasonInfo(season.id(), season.name(), season.startAt(), season.endAt(),
                    SeasonStatus.ACTIVE, season.createdAt()));
            return true;
        }
    }

    /** La "hora de la base" es {@link Fakes#NOW}: la vigencia nunca depende del reloj de la prueba. */
    static final class InMemoryCallStore implements CallStore {

        final Map<UUID, CallDetail> calls = new LinkedHashMap<>();
        final List<CallExceptionInfo> exceptions = new ArrayList<>();

        CallDetail put(UUID seasonId, CallStatus status, Instant startAt, Instant endAt) {
            CallDetail call = detail(UUID.randomUUID(), seasonId, "Convocatoria", startAt, endAt, "Instrucciones", status);
            calls.put(call.id(), call);
            return call;
        }

        private static CallDetail detail(UUID id, UUID seasonId, String name, Instant startAt, Instant endAt,
                                         String instructions, CallStatus status) {
            boolean open = !startAt.isAfter(Fakes.NOW) && Fakes.NOW.isBefore(endAt);
            return new CallDetail(id, seasonId, name, startAt, endAt, instructions, status, open, Fakes.NOW, Fakes.NOW);
        }

        @Override
        public Instant now() {
            return Fakes.NOW;
        }

        @Override
        public CallDetail insert(UUID seasonId, String name, Instant startAt, Instant endAt, String instructions,
                                 UUID createdBy) {
            CallDetail call = detail(UUID.randomUUID(), seasonId, name, startAt, endAt, instructions, CallStatus.DRAFT);
            calls.put(call.id(), call);
            return call;
        }

        @Override
        public Optional<CallDetail> findById(UUID callId) {
            return Optional.ofNullable(calls.get(callId));
        }

        @Override
        public Optional<CallDetail> findByIdForUpdate(UUID callId) {
            return findById(callId);
        }

        @Override
        public List<CallDetail> find(UUID seasonId, CallStatus status) {
            return calls.values().stream()
                    .filter(c -> seasonId == null || c.seasonId().equals(seasonId))
                    .filter(c -> status == null || c.status() == status)
                    .sorted(Comparator.comparing(CallDetail::startAt).reversed())
                    .toList();
        }

        @Override
        public void update(UUID callId, String name, Instant startAt, Instant endAt, String instructions) {
            CallDetail call = calls.get(callId);
            calls.put(callId, detail(callId, call.seasonId(), name, startAt, endAt, instructions, call.status()));
        }

        @Override
        public boolean publish(UUID callId) {
            CallDetail call = calls.get(callId);
            if (call == null || call.status() != CallStatus.DRAFT || !call.endAt().isAfter(Fakes.NOW)) {
                return false;
            }
            calls.put(callId, detail(callId, call.seasonId(), call.name(), call.startAt(), call.endAt(),
                    call.instructions(), CallStatus.PUBLISHED));
            return true;
        }

        @Override
        public boolean cancel(UUID callId) {
            CallDetail call = calls.get(callId);
            if (call == null || call.status() == CallStatus.CANCELLED) {
                return false;
            }
            calls.put(callId, detail(callId, call.seasonId(), call.name(), call.startAt(), call.endAt(),
                    call.instructions(), CallStatus.CANCELLED));
            return true;
        }

        @Override
        public CallExceptionInfo insertException(UUID callId, UUID guardianId, String reason, UUID grantedBy,
                                                 Instant expiresAt) {
            CallExceptionInfo info = new CallExceptionInfo(UUID.randomUUID(), callId, guardianId, reason, grantedBy,
                    Fakes.NOW, expiresAt, expiresAt.isAfter(Fakes.NOW));
            exceptions.add(info);
            return info;
        }

        @Override
        public List<CallExceptionInfo> findExceptions(UUID callId) {
            return exceptions.stream().filter(e -> e.callId().equals(callId)).toList();
        }
    }

    /** Categorías en memoria. {@code occupied} se fija a mano: la ocupación real la calculan las asignaciones del comité. */
    static class InMemoryCategories implements CategoryStore {

        final Map<UUID, CategoryInfo> rows = new LinkedHashMap<>();

        CategoryInfo add(String name, int min, int max, int capacity, int occupied, boolean active) {
            CategoryInfo category = new CategoryInfo(UUID.randomUUID(), name, min, max, capacity, occupied, null, active);
            rows.put(category.id(), category);
            return category;
        }

        @Override
        public CategoryInfo insert(String name, int min, int max, int capacity, UUID nextCategoryId, UUID createdBy) {
            checkUniqueness(null, name, min, max, true);
            CategoryInfo category = new CategoryInfo(UUID.randomUUID(), name, min, max, capacity, 0, nextCategoryId, true);
            rows.put(category.id(), category);
            return category;
        }

        /** Replica las restricciones de la base: nombre único y sin solape entre activas (V14). */
        private void checkUniqueness(UUID self, String name, int min, int max, boolean active) {
            for (CategoryInfo other : rows.values()) {
                if (other.id().equals(self)) {
                    continue;
                }
                if (other.name().equals(name)) {
                    throw new DuplicateCategoryNameException();
                }
                if (active && other.active() && other.birthYearMin() <= max && min <= other.birthYearMax()) {
                    throw new OverlappingCategoryRangeException();
                }
            }
        }

        @Override
        public List<CategoryInfo> findAll(boolean activeOnly) {
            return rows.values().stream().filter(c -> !activeOnly || c.active())
                    .sorted(Comparator.comparingInt(CategoryInfo::birthYearMin).reversed()).toList();
        }

        @Override
        public Optional<CategoryInfo> findById(UUID categoryId) {
            return Optional.ofNullable(rows.get(categoryId));
        }

        @Override
        public Optional<CategoryInfo> findByIdForUpdate(UUID categoryId) {
            return findById(categoryId);
        }

        @Override
        public Optional<CategoryInfo> findActiveCovering(int birthYear) {
            return rows.values().stream().filter(c -> c.active() && c.covers(birthYear)).findFirst();
        }

        @Override
        public Optional<CategoryInfo> findActiveOverlapping(int min, int max, UUID exclude) {
            return rows.values().stream()
                    .filter(c -> c.active() && !c.id().equals(exclude) && c.birthYearMin() <= max && min <= c.birthYearMax())
                    .findFirst();
        }

        @Override
        public void update(UUID categoryId, String name, int min, int max, int capacity, UUID nextCategoryId, boolean active) {
            checkUniqueness(categoryId, name, min, max, active);
            CategoryInfo current = rows.get(categoryId);
            rows.put(categoryId, new CategoryInfo(categoryId, name, min, max, capacity, current.occupied(), nextCategoryId, active));
        }
    }
}
