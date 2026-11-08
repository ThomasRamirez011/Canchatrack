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
import co.edu.unipamplona.canchatrack.identity.domain.RefreshToken;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;
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
                    lockedUntil, u.roles()));
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
                    AccountStatus.ACTIVE, 0, null, user.roles());
            byId.put(created.id(), created);
            return created;
        }

        UserAccount byEmail(String email) {
            return findByEmailForUpdate(email).orElseThrow();
        }

        void setStatus(UUID id, AccountStatus status) {
            UserAccount u = byId.get(id);
            byId.put(id, new UserAccount(id, u.email(), u.fullName(), u.passwordHash(), status, u.failedLoginAttempts(),
                    u.lockedUntil(), u.roles()));
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
}
