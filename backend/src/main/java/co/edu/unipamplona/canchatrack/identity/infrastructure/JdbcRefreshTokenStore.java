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
import co.edu.unipamplona.canchatrack.identity.application.RefreshTokenStore;
import co.edu.unipamplona.canchatrack.identity.domain.RefreshToken;

@Repository
public class JdbcRefreshTokenStore implements RefreshTokenStore {

    private static final String INSERT_SQL = """
            INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at, family_expires_at,
                                        ip, user_agent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::inet, ?)
            """;

    private static final String SELECT_BY_HASH = """
            SELECT id, user_id, family_id, issued_at, expires_at, family_expires_at, used_at, revoked_at
            FROM refresh_tokens
            WHERE token_hash = ?
            FOR UPDATE
            """;

    private static final int MAX_USER_AGENT = 500;

    private final JdbcTemplate jdbc;

    public JdbcRefreshTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(NewRefreshToken token) {
        ClientOrigin origin = token.origin() == null ? ClientOrigin.UNKNOWN : token.origin();
        jdbc.update(INSERT_SQL,
                token.id(), token.userId(), token.familyId(), token.tokenHash(),
                toOffset(token.issuedAt()), toOffset(token.expiresAt()), toOffset(token.familyExpiresAt()),
                hostAddress(origin.ip()), truncate(origin.userAgent()));
    }

    @Override
    public Optional<RefreshToken> findByHashForUpdate(String tokenHash) {
        List<RefreshToken> rows = jdbc.query(SELECT_BY_HASH, (rs, rowNum) -> map(rs), tokenHash);
        return rows.stream().findFirst();
    }

    @Override
    public void markUsed(UUID id, Instant at) {
        jdbc.update("UPDATE refresh_tokens SET used_at = ? WHERE id = ? AND used_at IS NULL", toOffset(at), id);
    }

    @Override
    public void revokeFamily(UUID familyId, Instant at, String reason) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ?, revoked_reason = ? WHERE family_id = ? AND revoked_at IS NULL",
                toOffset(at), reason, familyId);
    }

    private static RefreshToken map(ResultSet rs) throws SQLException {
        return new RefreshToken(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("family_id", UUID.class),
                toInstant(rs.getObject("issued_at", OffsetDateTime.class)),
                toInstant(rs.getObject("expires_at", OffsetDateTime.class)),
                toInstant(rs.getObject("family_expires_at", OffsetDateTime.class)),
                toInstant(rs.getObject("used_at", OffsetDateTime.class)),
                toInstant(rs.getObject("revoked_at", OffsetDateTime.class)));
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > MAX_USER_AGENT ? userAgent.substring(0, MAX_USER_AGENT) : userAgent;
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
