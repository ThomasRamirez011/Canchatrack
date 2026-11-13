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

import co.edu.unipamplona.canchatrack.identity.application.AccountTokenStore;
import co.edu.unipamplona.canchatrack.identity.domain.AccountToken;
import co.edu.unipamplona.canchatrack.identity.domain.AccountTokenPurpose;

@Repository
class JdbcAccountTokenStore implements AccountTokenStore {

    private final JdbcTemplate jdbc;

    JdbcAccountTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(NewAccountToken token) {
        jdbc.update("""
                INSERT INTO account_tokens (id, user_id, purpose, token_hash, created_at, expires_at, ip)
                VALUES (?, ?, ?, ?, ?, ?, ?::inet)
                """, token.id(), token.userId(), token.purpose().name(), token.tokenHash(), toOffset(token.createdAt()),
                toOffset(token.expiresAt()), hostAddress(token.ip()));
    }

    @Override
    public Optional<AccountToken> findByHashForUpdate(String tokenHash) {
        List<AccountToken> rows = jdbc.query("""
                SELECT id, user_id, purpose, expires_at, used_at, revoked_at
                FROM account_tokens
                WHERE token_hash = ?
                FOR UPDATE
                """, (rs, rowNum) -> map(rs), tokenHash);
        return rows.stream().findFirst();
    }

    @Override
    public void markUsed(UUID id, Instant at) {
        jdbc.update("UPDATE account_tokens SET used_at = ? WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL",
                toOffset(at), id);
    }

    @Override
    public void revokeOutstanding(UUID userId, AccountTokenPurpose purpose, Instant at) {
        jdbc.update("""
                UPDATE account_tokens SET revoked_at = ?
                WHERE user_id = ? AND purpose = ? AND used_at IS NULL AND revoked_at IS NULL
                """, toOffset(at), userId, purpose.name());
    }

    @Override
    public int deleteFinishedBefore(Instant cutoff) {
        return jdbc.update("""
                DELETE FROM account_tokens
                WHERE expires_at < ? OR used_at < ? OR revoked_at < ?
                """, toOffset(cutoff), toOffset(cutoff), toOffset(cutoff));
    }

    private static AccountToken map(ResultSet rs) throws SQLException {
        return new AccountToken(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                AccountTokenPurpose.valueOf(rs.getString("purpose")),
                toInstant(rs.getObject("expires_at", OffsetDateTime.class)),
                toInstant(rs.getObject("used_at", OffsetDateTime.class)),
                toInstant(rs.getObject("revoked_at", OffsetDateTime.class)));
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** {@code inet} no admite el identificador de zona IPv6 ("fe80::1%eth0"). */
    private static String hostAddress(InetAddress ip) {
        if (ip == null) {
            return null;
        }
        String address = ip.getHostAddress();
        int zone = address.indexOf('%');
        return zone >= 0 ? address.substring(0, zone) : address;
    }
}
