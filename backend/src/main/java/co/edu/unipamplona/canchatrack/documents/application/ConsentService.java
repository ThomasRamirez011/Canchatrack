package co.edu.unipamplona.canchatrack.documents.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.documents.api.ConsentKind;
import co.edu.unipamplona.canchatrack.documents.api.ConsentRecorder;

@Service
public class ConsentService implements ConsentRecorder {

    private static final int MAX_VERSION_TEXT = 50;

    private final ConsentStore store;

    public ConsentService(ConsentStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAuthenticatedAcceptance(UUID guardianId, UUID playerId, ConsentKind kind, String versionText) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(kind, "kind");
        if (versionText == null || versionText.isBlank() || versionText.length() > MAX_VERSION_TEXT) {
            throw new IllegalArgumentException("versionText is required (max " + MAX_VERSION_TEXT + " characters)");
        }
        store.insertAuthenticatedAcceptance(guardianId, playerId, kind, versionText);
    }
}
