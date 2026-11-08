package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.documents.api.ConsentKind;
import co.edu.unipamplona.canchatrack.documents.application.ConsentStore;

@Repository
public class JdbcConsentStore implements ConsentStore {

    private final JdbcTemplate jdbc;

    public JdbcConsentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertAuthenticatedAcceptance(UUID guardianId, UUID playerId, ConsentKind kind, String versionText) {
        jdbc.update("""
                INSERT INTO consents (guardian_id, player_id, kind, version_text, method)
                VALUES (?, ?, ?, ?, 'AUTHENTICATED_ACCEPTANCE')
                """, guardianId, playerId, kind.name(), versionText);
    }
}
