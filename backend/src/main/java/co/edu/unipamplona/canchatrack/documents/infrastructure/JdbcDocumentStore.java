package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.documents.application.DocumentStore;
import co.edu.unipamplona.canchatrack.documents.application.DocumentTypeInfo;
import co.edu.unipamplona.canchatrack.documents.application.DocumentVersionInfo;
import co.edu.unipamplona.canchatrack.documents.application.DocumentWindowInfo;
import co.edu.unipamplona.canchatrack.documents.application.DuplicateDocumentTypeException;
import co.edu.unipamplona.canchatrack.documents.application.ExpiringDocument;

/**
 * Tipos, ventanas y versiones de documentos. Dos consultas leen tablas de otro módulo por necesidad de la regla y no por
 * comodidad: el entrenador titular vigente ({@code category_assignments}) y el estado y nombre del jugador
 * ({@code players}); son solo lecturas y no hay otra fachada que las ofrezca sin crear un ciclo players ↔ documents.
 */
@Repository
public class JdbcDocumentStore implements DocumentStore {

    private static final String TYPE_COLUMNS = """
            id, code, name, mandatory, requires_expiry, validity_days, contains_health_data, max_size_bytes,
            allowed_content_types, active
            """;

    private static final RowMapper<DocumentTypeInfo> TYPE = (rs, rowNum) -> {
        Array formats = rs.getArray("allowed_content_types");
        List<String> allowed = formats == null ? List.of() : Arrays.asList((String[]) formats.getArray());
        return new DocumentTypeInfo(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("mandatory"), rs.getBoolean("requires_expiry"), (Integer) rs.getObject("validity_days"),
                rs.getBoolean("contains_health_data"), (Long) rs.getObject("max_size_bytes"), allowed, rs.getBoolean("active"));
    };

    private static final RowMapper<DocumentWindowInfo> WINDOW = (rs, rowNum) -> new DocumentWindowInfo(
            rs.getObject("id", UUID.class), rs.getObject("player_id", UUID.class), rs.getObject("document_type_id", UUID.class),
            instant(rs, "opens_at"), instant(rs, "closes_at"), rs.getString("reason"), rs.getObject("opened_by", UUID.class),
            instant(rs, "created_at"));

    private static final String VERSION_SELECT = """
            SELECT dv.id, dv.player_id, dv.document_type_id, dv.version_number, dv.file_id, f.original_name, f.content_type,
                   f.size_bytes, dv.issued_on, dv.expires_on, dv.notes, dv.uploaded_by, dv.uploaded_at
            FROM document_versions dv
                     JOIN stored_files f ON f.id = dv.file_id
            """;

    private static final RowMapper<DocumentVersionInfo> VERSION = (rs, rowNum) -> new DocumentVersionInfo(
            rs.getObject("id", UUID.class), rs.getObject("player_id", UUID.class), rs.getObject("document_type_id", UUID.class),
            rs.getInt("version_number"), rs.getObject("file_id", UUID.class), rs.getString("original_name"),
            rs.getString("content_type"), rs.getLong("size_bytes"), rs.getObject("issued_on", LocalDate.class),
            rs.getObject("expires_on", LocalDate.class), rs.getString("notes"), rs.getObject("uploaded_by", UUID.class),
            instant(rs, "uploaded_at"));

    private final JdbcTemplate jdbc;

    public JdbcDocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // ---------- tipos ----------

    @Override
    public List<DocumentTypeInfo> findTypes(boolean activeOnly) {
        return jdbc.query("SELECT " + TYPE_COLUMNS + " FROM document_types "
                + (activeOnly ? "WHERE active " : "") + "ORDER BY mandatory DESC, name", TYPE);
    }

    @Override
    public Optional<DocumentTypeInfo> findType(UUID id) {
        return jdbc.query("SELECT " + TYPE_COLUMNS + " FROM document_types WHERE id = ?", TYPE, id).stream().findFirst();
    }

    @Override
    public DocumentTypeInfo insertType(String code, String name, boolean mandatory, boolean requiresExpiry, Integer validityDays,
                                       boolean containsHealthData, Long maxSizeBytes, List<String> allowedContentTypes) {
        try {
            return jdbc.execute((Connection con) -> {
                try (PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO document_types (code, name, mandatory, requires_expiry, validity_days, contains_health_data,
                                                    max_size_bytes, allowed_content_types)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING""" + " " + TYPE_COLUMNS)) {
                    ps.setString(1, code);
                    ps.setString(2, name);
                    ps.setBoolean(3, mandatory);
                    ps.setBoolean(4, requiresExpiry);
                    setNullableInt(ps, 5, validityDays);
                    ps.setBoolean(6, containsHealthData);
                    setNullableLong(ps, 7, maxSizeBytes);
                    setFormats(con, ps, 8, allowedContentTypes);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        return TYPE.mapRow(rs, 1);
                    }
                }
            });
        } catch (DuplicateKeyException e) {
            throw new DuplicateDocumentTypeException(code);
        }
    }

    @Override
    public void updateType(UUID id, String name, boolean mandatory, boolean requiresExpiry, Integer validityDays, Long maxSizeBytes,
                           List<String> allowedContentTypes, boolean active) {
        jdbc.execute((Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement("""
                    UPDATE document_types
                    SET name = ?, mandatory = ?, requires_expiry = ?, validity_days = ?, max_size_bytes = ?,
                        allowed_content_types = ?, active = ?
                    WHERE id = ?""")) {
                ps.setString(1, name);
                ps.setBoolean(2, mandatory);
                ps.setBoolean(3, requiresExpiry);
                setNullableInt(ps, 4, validityDays);
                setNullableLong(ps, 5, maxSizeBytes);
                setFormats(con, ps, 6, allowedContentTypes);
                ps.setBoolean(7, active);
                ps.setObject(8, id);
                return ps.executeUpdate();
            }
        });
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    /** Sin formatos = sin restricción propia del tipo: se guarda NULL. */
    private static void setFormats(Connection con, PreparedStatement ps, int index, List<String> formats) throws SQLException {
        if (formats == null || formats.isEmpty()) {
            ps.setNull(index, Types.ARRAY);
        } else {
            ps.setArray(index, con.createArrayOf("text", formats.toArray(new String[0])));
        }
    }

    // ---------- jugador ----------

    @Override
    public boolean playerExists(UUID playerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM players WHERE id = ?)", Boolean.class, playerId));
    }

    @Override
    public boolean lockPlayer(UUID playerId) {
        return !jdbc.queryForList("SELECT id FROM players WHERE id = ? FOR UPDATE", UUID.class, playerId).isEmpty();
    }

    @Override
    public boolean isHeadCoachOf(UUID userId, UUID playerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM category_assignments
                               WHERE player_id = ? AND head_coach_id = ? AND valid_to IS NULL)
                """, Boolean.class, playerId, userId));
    }

    // ---------- ventanas ----------

    @Override
    public DocumentWindowInfo insertWindow(UUID documentTypeId, UUID playerId, Instant opensAt, Instant closesAt, String reason,
                                           UUID openedBy) {
        return jdbc.queryForObject("""
                INSERT INTO document_windows (player_id, document_type_id, opens_at, closes_at, reason, opened_by)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, player_id, document_type_id, opens_at, closes_at, reason, opened_by, created_at
                """, WINDOW, playerId, documentTypeId, OffsetDateTime.ofInstant(opensAt, java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(closesAt, java.time.ZoneOffset.UTC), reason, openedBy);
    }

    @Override
    public List<DocumentWindowInfo> findWindows(UUID documentTypeId, UUID playerId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, player_id, document_type_id, opens_at, closes_at, reason, opened_by, created_at
                FROM document_windows WHERE 1 = 1""");
        if (documentTypeId != null) {
            sql.append(" AND document_type_id = ?");
            args.add(documentTypeId);
        }
        if (playerId != null) {
            sql.append(" AND (player_id = ? OR player_id IS NULL)");
            args.add(playerId);
        }
        sql.append(" ORDER BY created_at DESC, id");
        return jdbc.query(sql.toString(), WINDOW, args.toArray());
    }

    @Override
    public boolean hasOpenWindow(UUID documentTypeId, UUID playerId, Instant at) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM document_windows
                               WHERE document_type_id = ? AND (player_id = ? OR player_id IS NULL)
                                 AND opens_at <= ? AND closes_at > ?)
                """, Boolean.class, documentTypeId, playerId, OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC)));
    }

    // ---------- versiones ----------

    @Override
    public Optional<DocumentVersionInfo> findCurrent(UUID playerId, UUID documentTypeId) {
        return jdbc.query(VERSION_SELECT + " WHERE dv.player_id = ? AND dv.document_type_id = ? ORDER BY dv.version_number DESC LIMIT 1",
                VERSION, playerId, documentTypeId).stream().findFirst();
    }

    @Override
    public List<DocumentVersionInfo> findCurrentOfPlayer(UUID playerId) {
        return jdbc.query(VERSION_SELECT + " JOIN current_document_versions cv ON cv.id = dv.id WHERE dv.player_id = ?",
                VERSION, playerId);
    }

    @Override
    public List<DocumentVersionInfo> findVersions(UUID playerId, UUID documentTypeId) {
        return jdbc.query(VERSION_SELECT + " WHERE dv.player_id = ? AND dv.document_type_id = ? ORDER BY dv.version_number DESC",
                VERSION, playerId, documentTypeId);
    }

    @Override
    public Optional<DocumentVersionInfo> findVersion(UUID versionId) {
        return jdbc.query(VERSION_SELECT + " WHERE dv.id = ?", VERSION, versionId).stream().findFirst();
    }

    @Override
    public DocumentVersionInfo insertVersion(UUID playerId, UUID documentTypeId, UUID fileId, LocalDate issuedOn, LocalDate expiresOn,
                                             String notes, UUID uploadedBy) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO document_versions (player_id, document_type_id, version_number, file_id, issued_on, expires_on, notes,
                                               uploaded_by)
                VALUES (?, ?, (SELECT COALESCE(MAX(version_number), 0) + 1 FROM document_versions
                               WHERE player_id = ? AND document_type_id = ?), ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class, playerId, documentTypeId, playerId, documentTypeId, fileId, issuedOn, expiresOn, notes, uploadedBy);
        return findVersion(id).orElseThrow();
    }

    @Override
    public boolean isFileAttached(UUID fileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM document_versions WHERE file_id = ?)", Boolean.class, fileId));
    }

    @Override
    public void lockFile(UUID fileId) {
        jdbc.queryForList("SELECT id FROM stored_files WHERE id = ? FOR UPDATE", UUID.class, fileId);
    }

    // ---------- requisitos y alertas ----------

    @Override
    public List<String> findMissingMandatory(UUID playerId, LocalDate onDate) {
        return jdbc.queryForList("""
                SELECT dt.name
                FROM document_types dt
                WHERE dt.active AND dt.mandatory
                  AND NOT EXISTS (SELECT 1 FROM current_document_versions cv
                                  WHERE cv.player_id = ? AND cv.document_type_id = dt.id
                                    AND (NOT dt.requires_expiry OR (cv.expires_on IS NOT NULL AND cv.expires_on >= ?)))
                ORDER BY dt.name
                """, String.class, playerId, onDate);
    }

    @Override
    public List<ExpiringDocument> findExpiring(LocalDate from, int withinDays) {
        return jdbc.query("""
                SELECT cv.id AS version_id, cv.player_id, p.code, p.first_name || ' ' || p.last_name AS player_name,
                       dt.name AS document_name, cv.expires_on, ca.head_coach_id
                FROM current_document_versions cv
                         JOIN document_types dt ON dt.id = cv.document_type_id AND dt.active AND dt.requires_expiry
                         JOIN players p ON p.id = cv.player_id
                              AND p.status IN ('PENDING_REGISTRATION', 'ACTIVE', 'IN_EVALUATION', 'PROMOTION_APPROVED')
                         LEFT JOIN category_assignments ca ON ca.player_id = cv.player_id AND ca.valid_to IS NULL
                WHERE cv.expires_on BETWEEN ? AND ?
                ORDER BY cv.expires_on, p.code
                """, (rs, rowNum) -> new ExpiringDocument(rs.getObject("version_id", UUID.class),
                        rs.getObject("player_id", UUID.class), rs.getString("code"), rs.getString("player_name"),
                        rs.getString("document_name"), rs.getObject("expires_on", LocalDate.class),
                        rs.getObject("head_coach_id", UUID.class)),
                from, from.plusDays(withinDays));
    }
}
