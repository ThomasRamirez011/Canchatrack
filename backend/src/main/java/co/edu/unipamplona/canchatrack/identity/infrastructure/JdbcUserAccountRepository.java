package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.identity.application.DuplicateEmailException;
import co.edu.unipamplona.canchatrack.identity.application.UserAccountRepository;
import co.edu.unipamplona.canchatrack.identity.domain.AccountStatus;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;

@Repository
public class JdbcUserAccountRepository implements UserAccountRepository {

    private static final String SELECT_USER = """
            SELECT id, email, full_name, password_hash, status, failed_login_attempts, locked_until, email_verified_at
            FROM users
            """;

    /**
     * La vigencia de un rol se evalúa con la hora de la BASE DE DATOS, no la de la aplicación: {@code valid_from} se
     * escribe con {@code now()} de la base, y con un desfase de reloj entre servidores una cuenta recién creada podría
     * quedar sin roles (y recibir un access token vacío durante todo su tiempo de vida).
     */
    private static final String SELECT_ROLES = """
            SELECT r.code
            FROM user_roles ur
                     JOIN roles r ON r.id = ur.role_id
            WHERE ur.user_id = ?
              AND ur.valid_from <= now()
              AND (ur.valid_to IS NULL OR ur.valid_to > now())
            """;

    private final JdbcTemplate jdbc;

    public JdbcUserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByEmailForUpdate(String normalizedEmail) {
        return findOne(SELECT_USER + " WHERE lower(email) = lower(?) FOR UPDATE", normalizedEmail);
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return findOne(SELECT_USER + " WHERE id = ?", id);
    }

    @Override
    public Optional<UserAccount> findByIdForUpdate(UUID id) {
        return findOne(SELECT_USER + " WHERE id = ? FOR UPDATE", id);
    }

    @Override
    public boolean existsByEmail(String normalizedEmail) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(?))", Boolean.class, normalizedEmail);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public long count() {
        Long total = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public void recordFailedLogin(UUID id, int failedAttempts, Instant lockedUntil) {
        jdbc.update("UPDATE users SET failed_login_attempts = ?, locked_until = ? WHERE id = ?",
                failedAttempts, toOffset(lockedUntil), id);
    }

    @Override
    public void recordSuccessfulLogin(UUID id, Instant at) {
        jdbc.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL, last_login_at = ? WHERE id = ?",
                toOffset(at), id);
    }

    @Override
    public void updatePassword(UUID id, String passwordHash, Instant at) {
        jdbc.update("""
                UPDATE users
                SET password_hash = ?, failed_login_attempts = 0, locked_until = NULL,
                    email_verified_at = COALESCE(email_verified_at, ?)
                WHERE id = ?
                """, passwordHash, toOffset(at), id);
    }

    @Override
    public void markEmailVerified(UUID id, Instant at) {
        jdbc.update("UPDATE users SET email_verified_at = ? WHERE id = ? AND email_verified_at IS NULL", toOffset(at), id);
    }

    @Override
    public UserAccount create(NewUser user) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO users (id, email, full_name, password_hash, created_by, email_verified_at)
                    VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() END)
                    """, id, user.email(), user.fullName(), user.passwordHash(), user.createdBy(), user.emailVerified());
        } catch (DuplicateKeyException e) {
            throw new DuplicateEmailException();
        }
        for (RoleCode role : user.roles()) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id, created_by) SELECT ?, id, ? FROM roles WHERE code = ?",
                    id, user.createdBy(), role.name());
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("user just inserted was not found: " + id));
    }

    private Optional<UserAccount> findOne(String sql, Object arg) {
        List<UserRow> rows = jdbc.query(sql, (rs, rowNum) -> new UserRow(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getInt("failed_login_attempts"),
                rs.getObject("locked_until", OffsetDateTime.class),
                rs.getObject("email_verified_at", OffsetDateTime.class)), arg);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        UserRow row = rows.get(0);
        return Optional.of(new UserAccount(
                row.id(), row.email(), row.fullName(), row.passwordHash(), AccountStatus.parse(row.status()),
                row.failedAttempts(), row.lockedUntil() == null ? null : row.lockedUntil().toInstant(),
                loadRoles(row.id()), row.emailVerifiedAt() == null ? null : row.emailVerifiedAt().toInstant()));
    }

    private Set<RoleCode> loadRoles(UUID userId) {
        List<String> codes = jdbc.queryForList(SELECT_ROLES, String.class, userId);
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        for (String code : codes) {
            RoleCode.parse(code).ifPresent(roles::add);
        }
        return roles;
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record UserRow(UUID id, String email, String fullName, String passwordHash, String status,
                           int failedAttempts, OffsetDateTime lockedUntil, OffsetDateTime emailVerifiedAt) {
    }
}
