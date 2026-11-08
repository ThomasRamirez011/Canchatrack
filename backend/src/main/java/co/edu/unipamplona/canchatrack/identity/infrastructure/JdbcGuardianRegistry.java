package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.identity.application.DuplicateGuardianDocumentException;
import co.edu.unipamplona.canchatrack.identity.application.GuardianRegistry;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

@Repository
public class JdbcGuardianRegistry implements GuardianRegistry {

    private final JdbcTemplate jdbc;

    public JdbcGuardianRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> findGuardianId(UUID userId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM guardians WHERE user_id = ?", UUID.class, userId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    @Override
    public boolean existsByDocument(IdDocumentType type, String normalizedNumber) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM guardians WHERE document_type = ? AND document_number = ?)",
                Boolean.class, type.name(), normalizedNumber);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public UUID create(UUID userId, IdDocumentType type, String normalizedNumber, String phone) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO guardians (id, user_id, document_type, document_number, phone) VALUES (?, ?, ?, ?, ?)",
                    id, userId, type.name(), normalizedNumber, phone);
        } catch (DuplicateKeyException e) {
            throw new DuplicateGuardianDocumentException();
        }
        return id;
    }
}
