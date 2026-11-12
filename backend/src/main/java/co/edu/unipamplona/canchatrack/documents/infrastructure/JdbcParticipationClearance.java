package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.documents.api.ParticipationClearance;

@Repository
public class JdbcParticipationClearance implements ParticipationClearance {

    private static final int MAX_VERSION_TEXT = 50;

    private final JdbcTemplate jdbc;

    public JdbcParticipationClearance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> withoutValidMedicalCertificate(Collection<UUID> playerIds, LocalDate onDate) {
        if (playerIds.isEmpty()) {
            return Set.of();
        }
        // Válido = la versión vigente del tipo MEDICAL_CERTIFICATE tiene fecha de vencimiento no anterior a la fecha.
        Set<UUID> valid = new HashSet<>(jdbc.queryForList("""
                SELECT cv.player_id
                FROM current_document_versions cv
                         JOIN document_types dt ON dt.id = cv.document_type_id
                WHERE dt.code = 'MEDICAL_CERTIFICATE'
                  AND cv.expires_on IS NOT NULL AND cv.expires_on >= ?
                  AND cv.player_id IN (%s)
                """.formatted(placeholders(playerIds)), UUID.class, params(onDate, playerIds)));
        return missing(playerIds, valid);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> withoutValidParticipationAuthorization(Collection<UUID> playerIds, Instant at) {
        if (playerIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> valid = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT c.player_id
                FROM consents c
                WHERE c.kind = 'PARTICIPATION'
                  AND c.revoked_at IS NULL
                  AND c.accepted_at <= ?
                  AND (c.valid_until IS NULL OR c.valid_until > ?)
                  AND c.player_id IN (%s)
                """.formatted(placeholders(playerIds)), UUID.class, params(at, at, playerIds)));
        return missing(playerIds, valid);
    }

    @Override
    @Transactional
    public boolean authorizeMatch(UUID guardianId, UUID playerId, UUID matchId, String versionText) {
        if (versionText == null || versionText.isBlank() || versionText.length() > MAX_VERSION_TEXT) {
            throw new IllegalArgumentException("versionText is required (max " + MAX_VERSION_TEXT + " characters)");
        }
        int inserted = jdbc.update("""
                INSERT INTO consents (guardian_id, player_id, kind, match_id, version_text, method)
                VALUES (?, ?, 'MATCH_AUTHORIZATION', ?, ?, 'AUTHENTICATED_ACCEPTANCE')
                ON CONFLICT (match_id, player_id) WHERE kind = 'MATCH_AUTHORIZATION' AND revoked_at IS NULL DO NOTHING
                """, guardianId, playerId, matchId, versionText);
        return inserted > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> matchAuthorizedPlayers(UUID matchId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT player_id FROM consents
                WHERE kind = 'MATCH_AUTHORIZATION' AND match_id = ? AND revoked_at IS NULL
                """, UUID.class, matchId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> authorizedMatchesOf(UUID playerId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT match_id FROM consents
                WHERE kind = 'MATCH_AUTHORIZATION' AND player_id = ? AND revoked_at IS NULL
                """, UUID.class, playerId));
    }

    private static String placeholders(Collection<UUID> ids) {
        return String.join(", ", Collections.nCopies(ids.size(), "?"));
    }

    private static Object[] params(Object first, Collection<UUID> ids) {
        return join(new Object[]{toDb(first)}, ids);
    }

    private static Object[] params(Object first, Object second, Collection<UUID> ids) {
        return join(new Object[]{toDb(first), toDb(second)}, ids);
    }

    private static Object[] join(Object[] head, Collection<UUID> ids) {
        Object[] all = new Object[head.length + ids.size()];
        System.arraycopy(head, 0, all, 0, head.length);
        int i = head.length;
        for (UUID id : ids) {
            all[i++] = id;
        }
        return all;
    }

    private static Object toDb(Object value) {
        return value instanceof Instant instant ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : value;
    }

    private static Set<UUID> missing(Collection<UUID> requested, Set<UUID> valid) {
        Set<UUID> result = new HashSet<>(requested);
        result.removeAll(valid);
        return result;
    }
}
