package co.edu.unipamplona.canchatrack.documents.application;

import java.util.UUID;

import co.edu.unipamplona.canchatrack.documents.api.ConsentKind;

/** Puerto de salida: persistencia de consentimientos. */
public interface ConsentStore {

    void insertAuthenticatedAcceptance(UUID guardianId, UUID playerId, ConsentKind kind, String versionText);
}
