package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.net.InetAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.identity.application.ClientOrigin;
import co.edu.unipamplona.canchatrack.identity.application.MfaStore;
import co.edu.unipamplona.canchatrack.identity.domain.MfaStep;

@Repository
class JdbcMfaStore implements MfaStore {

    private final JdbcTemplate jdbc;

    JdbcMfaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Factor> findFactorForUpdate(UUID userId) {
        return jdbc.query("""
                SELECT user_id, secret_sealed, status, last_used_step
                FROM mfa_factors WHERE user_id = ? FOR UPDATE
                """, (rs, rowNum) -> factor(rs), userId).stream().findFirst();
    }

    @Override
    public Optional<Factor> findFactor(UUID userId) {
        return jdbc.query("""
                SELECT user_id, secret_sealed, status, last_used_step
                FROM mfa_factors WHERE user_id = ?
                """, (rs, rowNum) -> factor(rs), userId).stream().findFirst();
    }

    @Override
    public boolean savePendingFactor(UUID userId, byte[] sealedSecret, Instant at) {
        // Inserta o reemplaza solo un factor sin confirmar; uno activo no se toca.
        int rows = jdbc.update("""
                INSERT INTO mfa_factors (user_id, secret_sealed, status, created_at)
                VALUES (?, ?, 'PENDING', ?)
                ON CONFLICT (user_id) DO UPDATE
                    SET secret_sealed = EXCLUDED.secret_sealed, created_at = EXCLUDED.created_at, last_used_step = NULL
                    WHERE mfa_factors.status = 'PENDING'
                """, userId, sealedSecret, toOffset(at));
        return rows == 1;
    }

    @Override
    public void activateFactor(UUID userId, long usedStep, Instant at) {
        jdbc.update("""
                UPDATE mfa_factors SET status = 'ACTIVE', confirmed_at = ?, last_used_step = ?
                WHERE user_id = ? AND status = 'PENDING'
                """, toOffset(at), usedStep, userId);
    }

    @Override
    public void recordUsedStep(UUID userId, long step) {
        jdbc.update("""
                UPDATE mfa_factors SET last_used_step = ?
                WHERE user_id = ? AND (last_used_step IS NULL OR last_used_step < ?)
                """, step, userId, step);
    }

    @Override
    public void deleteFactorAndCodes(UUID userId) {
        jdbc.update("DELETE FROM mfa_backup_codes WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM mfa_factors WHERE user_id = ?", userId);
    }

    @Override
    public void insertChallenge(NewChallenge challenge) {
        ClientOrigin origin = challenge.origin() == null ? ClientOrigin.UNKNOWN : challenge.origin();
        jdbc.update("""
                INSERT INTO mfa_challenges (id, user_id, token_hash, purpose, created_at, expires_at, ip)
                VALUES (?, ?, ?, ?, ?, ?, ?::inet)
                """, challenge.id(), challenge.userId(), challenge.tokenHash(), challenge.purpose().name(),
                toOffset(challenge.createdAt()), toOffset(challenge.expiresAt()), hostAddress(origin.ip()));
    }

    @Override
    public Optional<Challenge> findChallengeForUpdate(String tokenHash) {
        return jdbc.query("""
                SELECT id, user_id, purpose, expires_at, attempts, consumed_at
                FROM mfa_challenges WHERE token_hash = ? FOR UPDATE
                """, (rs, rowNum) -> new Challenge(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                MfaStep.valueOf(rs.getString("purpose")),
                toInstant(rs.getObject("expires_at", OffsetDateTime.class)),
                rs.getInt("attempts"),
                toInstant(rs.getObject("consumed_at", OffsetDateTime.class))), tokenHash).stream().findFirst();
    }

    @Override
    public void recordChallengeAttempt(UUID challengeId) {
        jdbc.update("UPDATE mfa_challenges SET attempts = attempts + 1 WHERE id = ?", challengeId);
    }

    @Override
    public void consumeChallenge(UUID challengeId, Instant at) {
        jdbc.update("UPDATE mfa_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL",
                toOffset(at), challengeId);
    }

    @Override
    public void consumeOpenChallenges(UUID userId, Instant at) {
        jdbc.update("UPDATE mfa_challenges SET consumed_at = ? WHERE user_id = ? AND consumed_at IS NULL",
                toOffset(at), userId);
    }

    @Override
    public void replaceBackupCodes(UUID userId, List<String> fingerprints, Instant at) {
        jdbc.update("DELETE FROM mfa_backup_codes WHERE user_id = ?", userId);
        for (String fingerprint : fingerprints) {
            jdbc.update("INSERT INTO mfa_backup_codes (user_id, code_hash, created_at) VALUES (?, ?, ?)",
                    userId, fingerprint, toOffset(at));
        }
    }

    @Override
    public boolean consumeBackupCode(UUID userId, String fingerprint, Instant at) {
        return jdbc.update("""
                UPDATE mfa_backup_codes SET used_at = ?
                WHERE user_id = ? AND code_hash = ? AND used_at IS NULL
                """, toOffset(at), userId, fingerprint) == 1;
    }

    @Override
    public int countUnusedBackupCodes(UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM mfa_backup_codes WHERE user_id = ? AND used_at IS NULL", Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public int deleteChallengesBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM mfa_challenges WHERE expires_at < ? OR consumed_at < ?",
                toOffset(cutoff), toOffset(cutoff));
    }

    private static Factor factor(ResultSet rs) throws SQLException {
        long step = rs.getLong("last_used_step");
        Long lastUsed = rs.wasNull() ? null : step;
        return new Factor(rs.getObject("user_id", UUID.class), rs.getBytes("secret_sealed"),
                "ACTIVE".equals(rs.getString("status")), lastUsed);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String hostAddress(InetAddress ip) {
        if (ip == null) {
            return null;
        }
        String address = ip.getHostAddress();
        int zone = address.indexOf('%');
        return zone >= 0 ? address.substring(0, zone) : address;
    }
}
