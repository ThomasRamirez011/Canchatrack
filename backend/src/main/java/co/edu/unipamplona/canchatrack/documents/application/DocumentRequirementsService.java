package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.documents.api.DocumentRequirements;

@Service
public class DocumentRequirementsService implements DocumentRequirements {

    private final DocumentStore store;
    private final DocumentSettings settings;
    private final Clock clock;

    public DocumentRequirementsService(DocumentStore store, DocumentSettings settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> missingMandatory(UUID playerId) {
        return store.findMissingMandatory(playerId, LocalDate.now(clock.withZone(settings.zone())));
    }
}
