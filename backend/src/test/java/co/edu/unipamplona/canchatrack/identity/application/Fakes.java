package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.domain.AccountStatus;
import co.edu.unipamplona.canchatrack.identity.domain.AccountToken;
import co.edu.unipamplona.canchatrack.identity.domain.AccountTokenPurpose;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;
import co.edu.unipamplona.canchatrack.identity.domain.RefreshToken;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

/** Dobles en memoria de los puertos de identity, compartidos por las pruebas de los casos de uso. */
final class Fakes {

    private Fakes() {
    }

    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-08T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }
    }

    static final class InMemoryUsers implements UserAccountRepository {

        final Map<UUID, UserAccount> byId = new HashMap<>();

        @Override
        public Optional<UserAccount> findByEmailForUpdate(String email) {
            return byId.values().stream().filter(u -> u.email().equals(email)).findFirst();
        }

        @Override
        public Optional<UserAccount> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<UserAccount> findByIdForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public boolean existsByEmail(String email) {
            return findByEmailForUpdate(email).isPresent();
        }

        @Override
        public long count() {
            return byId.size();
        }

        @Override
        public void recordFailedLogin(UUID id, int failedAttempts, Instant lockedUntil) {
            UserAccount u = byId.get(id);
            byId.put(id, new UserAccount(id, u.email(), u.fullName(), u.passwordHash(), u.status(), failedAttempts,
                    lockedUntil, u.roles(), u.emailVerifiedAt()));
        }

        @Override
        public void recordSuccessfulLogin(UUID id, Instant at) {
            recordFailedLogin(id, 0, null);
        }

        @Override
        public UserAccount create(NewUser user) {
            if (existsByEmail(user.email())) {
                throw new DuplicateEmailException();
            }
            UserAccount created = new UserAccount(UUID.randomUUID(), user.email(), user.fullName(), user.passwordHash(),
                    AccountStatus.ACTIVE, 0, null, user.roles(),
                    user.emailVerified() ? Instant.parse("2026-11-08T12:00:00Z") : null);
            byId.put(created.id(), created);
            return created;
        }

        @Override
        public void updatePassword(UUID id, String passwordHash, Instant at) {
            UserAccount u = byId.get(id);
            byId.put(id, new UserAccount(id, u.email(), u.fullName(), passwordHash, u.status(), 0, null, u.roles(),
                    u.emailVerifiedAt() == null ? at : u.emailVerifiedAt()));
        }

        @Override
        public void markEmailVerified(UUID id, Instant at) {
            UserAccount u = byId.get(id);
            if (u.emailVerifiedAt() == null) {
                byId.put(id, new UserAccount(id, u.email(), u.fullName(), u.passwordHash(), u.status(),
                        u.failedLoginAttempts(), u.lockedUntil(), u.roles(), at));
            }
        }

        UserAccount byEmail(String email) {
            return findByEmailForUpdate(email).orElseThrow();
        }

        void setStatus(UUID id, AccountStatus status) {
            UserAccount u = byId.get(id);
            byId.put(id, new UserAccount(id, u.email(), u.fullName(), u.passwordHash(), status, u.failedLoginAttempts(),
                    u.lockedUntil(), u.roles(), u.emailVerifiedAt()));
        }
    }

    static final class InMemoryGuardians implements GuardianRegistry {

        final Map<UUID, UUID> guardianIdByUser = new HashMap<>();
        final Map<String, UUID> userByDocument = new HashMap<>();
        final Map<UUID, String> phoneByUser = new HashMap<>();

        @Override
        public Optional<UUID> findGuardianId(UUID userId) {
            return Optional.ofNullable(guardianIdByUser.get(userId));
        }

        @Override
        public boolean existsById(UUID guardianId) {
            return guardianIdByUser.containsValue(guardianId);
        }

        @Override
        public boolean existsByDocument(IdDocumentType type, String normalizedNumber) {
            return userByDocument.containsKey(type + ":" + normalizedNumber);
        }

        @Override
        public UUID create(UUID userId, IdDocumentType type, String normalizedNumber, String phone) {
            if (existsByDocument(type, normalizedNumber)) {
                throw new DuplicateGuardianDocumentException();
            }
            UUID id = UUID.randomUUID();
            guardianIdByUser.put(userId, id);
            userByDocument.put(type + ":" + normalizedNumber, userId);
            phoneByUser.put(userId, phone);
            return id;
        }
    }

    static final class InMemoryRefreshTokens implements RefreshTokenStore {

        private record Row(NewRefreshToken token, Instant usedAt, Instant revokedAt) {
        }

        private final Map<String, Row> byHash = new LinkedHashMap<>();

        @Override
        public void insert(NewRefreshToken token) {
            byHash.put(token.tokenHash(), new Row(token, null, null));
        }

        @Override
        public Optional<RefreshToken> findByHashForUpdate(String tokenHash) {
            Row row = byHash.get(tokenHash);
            if (row == null) {
                return Optional.empty();
            }
            NewRefreshToken t = row.token();
            return Optional.of(new RefreshToken(t.id(), t.userId(), t.familyId(), t.issuedAt(), t.expiresAt(),
                    t.familyExpiresAt(), row.usedAt(), row.revokedAt()));
        }

        @Override
        public void markUsed(UUID id, Instant at) {
            byHash.replaceAll((k, r) -> r.token().id().equals(id) ? new Row(r.token(), at, r.revokedAt()) : r);
        }

        @Override
        public void revokeFamily(UUID familyId, Instant at, String reason) {
            byHash.replaceAll((k, r) -> r.token().familyId().equals(familyId) && r.revokedAt() == null
                    ? new Row(r.token(), r.usedAt(), at) : r);
        }

        @Override
        public void revokeAllOfUser(UUID userId, Instant at, String reason) {
            byHash.replaceAll((k, r) -> r.token().userId().equals(userId) && r.revokedAt() == null
                    ? new Row(r.token(), r.usedAt(), at) : r);
        }

        @Override
        public int deleteInactiveBefore(Instant cutoff) {
            int before = byHash.size();
            byHash.values().removeIf(r -> r.token().familyExpiresAt().isBefore(cutoff)
                    || (r.revokedAt() != null && r.revokedAt().isBefore(cutoff)));
            return before - byHash.size();
        }

        int size() {
            return byHash.size();
        }

        boolean allRevoked(UUID userId) {
            return byHash.values().stream().filter(r -> r.token().userId().equals(userId))
                    .allMatch(r -> r.revokedAt() != null);
        }

        boolean storesHashOnly(String rawToken) {
            return byHash.containsKey(OpaqueToken.sha256Hex(rawToken)) && !byHash.containsKey(rawToken);
        }

        Instant expiresAtOf(String rawToken) {
            return byHash.get(OpaqueToken.sha256Hex(rawToken)).token().expiresAt();
        }
    }

    static final class FakeHasher implements PasswordHasher {

        int simulated;

        @Override
        public String hash(String rawPassword) {
            return "hash:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String hash) {
            return hash != null && hash.equals("hash:" + rawPassword);
        }

        @Override
        public void simulateVerification(String rawPassword) {
            simulated++;
        }
    }

    static final class FakeIssuer implements AccessTokenIssuer {

        @Override
        public IssuedAccessToken issue(UUID userId, Set<RoleCode> roles, Instant now) {
            return new IssuedAccessToken("jwt-for-" + userId + "-" + roles, now.plusSeconds(900));
        }
    }

    static final class RecordingAudit implements AuditWriter {

        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void write(AuditRecord record) {
            records.add(record);
        }

        @Override
        public void writeIndependent(AuditRecord record) {
            records.add(record);
        }

        long count(String action) {
            return records.stream().filter(r -> r.action().equals(action)).count();
        }
    }

    static final class InMemoryAccountTokens implements AccountTokenStore {

        private record Row(NewAccountToken token, Instant usedAt, Instant revokedAt) {
        }

        private final Map<String, Row> byHash = new LinkedHashMap<>();

        @Override
        public void insert(NewAccountToken token) {
            byHash.put(token.tokenHash(), new Row(token, null, null));
        }

        @Override
        public Optional<AccountToken> findByHashForUpdate(String tokenHash) {
            Row row = byHash.get(tokenHash);
            if (row == null) {
                return Optional.empty();
            }
            NewAccountToken t = row.token();
            return Optional.of(new AccountToken(t.id(), t.userId(), t.purpose(), t.expiresAt(), row.usedAt(), row.revokedAt()));
        }

        @Override
        public void markUsed(UUID id, Instant at) {
            byHash.replaceAll((k, r) -> r.token().id().equals(id) && r.usedAt() == null && r.revokedAt() == null
                    ? new Row(r.token(), at, null) : r);
        }

        @Override
        public void revokeOutstanding(UUID userId, AccountTokenPurpose purpose, Instant at) {
            byHash.replaceAll((k, r) -> r.token().userId().equals(userId) && r.token().purpose() == purpose
                    && r.usedAt() == null && r.revokedAt() == null ? new Row(r.token(), null, at) : r);
        }

        @Override
        public int deleteFinishedBefore(Instant cutoff) {
            int before = byHash.size();
            byHash.values().removeIf(r -> r.token().expiresAt().isBefore(cutoff)
                    || (r.usedAt() != null && r.usedAt().isBefore(cutoff))
                    || (r.revokedAt() != null && r.revokedAt().isBefore(cutoff)));
            return before - byHash.size();
        }

        int size() {
            return byHash.size();
        }

        long open(UUID userId, AccountTokenPurpose purpose) {
            return byHash.values().stream().filter(r -> r.token().userId().equals(userId)
                    && r.token().purpose() == purpose && r.usedAt() == null && r.revokedAt() == null).count();
        }

        boolean storesHashOnly(String rawToken) {
            return byHash.containsKey(OpaqueToken.sha256Hex(rawToken)) && !byHash.containsKey(rawToken);
        }

        Instant expiresAtOf(String rawToken) {
            return byHash.get(OpaqueToken.sha256Hex(rawToken)).token().expiresAt();
        }
    }

    static final class InMemoryRateLimits implements RateLimitStore {

        private record Event(RateLimitBucket bucket, String subject, Instant at) {
        }

        private final List<Event> events = new ArrayList<>();

        @Override
        public Attempt tryRecord(RateLimitBucket bucket, String subjectHash, RateLimitRule rule, Instant now) {
            List<Instant> inWindow = events.stream()
                    .filter(e -> e.bucket() == bucket && e.subject().equals(subjectHash) && e.at().isAfter(rule.windowStart(now)))
                    .map(Event::at).toList();
            if (rule.isExceeded(inWindow.size())) {
                return new Attempt(false, rule.retryAfterSeconds(inWindow, now));
            }
            events.add(new Event(bucket, subjectHash, now));
            return new Attempt(true, 0);
        }

        @Override
        public int deleteOlderThan(Instant cutoff) {
            int before = events.size();
            events.removeIf(e -> e.at().isBefore(cutoff));
            return before - events.size();
        }

        int size() {
            return events.size();
        }
    }

    static final class RecordingQueue implements NotificationQueue {

        record Notice(String type, List<UUID> recipients, String subject, Map<String, String> payload,
                      String relatedEntityType, UUID relatedEntityId, String dedupScope) {
        }

        final List<Notice> notices = new ArrayList<>();

        @Override
        public void enqueue(String type, java.util.Collection<UUID> recipientUserIds, String subject,
                            Map<String, String> payload, String relatedEntityType, UUID relatedEntityId, String dedupScope) {
            notices.add(new Notice(type, List.copyOf(recipientUserIds), subject, Map.copyOf(payload), relatedEntityType,
                    relatedEntityId, dedupScope));
        }

        List<Notice> ofType(String type) {
            return notices.stream().filter(n -> n.type().equals(type)).toList();
        }
    }
}
