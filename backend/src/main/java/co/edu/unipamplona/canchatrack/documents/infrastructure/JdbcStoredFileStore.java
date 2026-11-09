package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.documents.application.StoredFile;
import co.edu.unipamplona.canchatrack.documents.application.StoredFileStore;

@Repository
public class JdbcStoredFileStore implements StoredFileStore {

    private static final RowMapper<StoredFile> MAPPER = (rs, rowNum) -> new StoredFile(
            rs.getObject("id", UUID.class), rs.getString("original_name"), rs.getString("content_type"),
            rs.getLong("size_bytes"), rs.getString("storage_key"), rs.getString("sha256"),
            rs.getObject("uploaded_by", UUID.class), rs.getTimestamp("uploaded_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcStoredFileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoredFile insert(String originalName, String contentType, long sizeBytes, String storageKey, String sha256,
                             UUID uploadedBy) {
        return jdbc.queryForObject("""
                INSERT INTO stored_files (original_name, content_type, size_bytes, storage_key, sha256, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, original_name, content_type, size_bytes, storage_key, sha256, uploaded_by, uploaded_at
                """, MAPPER, originalName, contentType, sizeBytes, storageKey, sha256, uploadedBy);
    }

    @Override
    public Optional<StoredFile> find(UUID fileId) {
        List<StoredFile> rows = jdbc.query("""
                SELECT id, original_name, content_type, size_bytes, storage_key, sha256, uploaded_by, uploaded_at
                FROM stored_files WHERE id = ?
                """, MAPPER, fileId);
        return rows.stream().findFirst();
    }
}
