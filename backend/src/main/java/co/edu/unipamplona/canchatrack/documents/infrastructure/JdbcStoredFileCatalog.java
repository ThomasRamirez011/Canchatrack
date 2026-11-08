package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.documents.application.StoredFileCatalog;

@Repository
public class JdbcStoredFileCatalog implements StoredFileCatalog {

    private final JdbcTemplate jdbc;

    public JdbcStoredFileCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(UUID fileId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM stored_files WHERE id = ?)", Boolean.class, fileId);
        return Boolean.TRUE.equals(exists);
    }
}
