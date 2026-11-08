package co.edu.unipamplona.canchatrack.documents.api;

import java.util.UUID;

/**
 * Fachada pública: registra el consentimiento que un acudiente autenticado otorga sobre un jugador (RF-011). Siempre
 * se ejecuta dentro de la transacción del llamador: si la operación de negocio se revierte, el consentimiento también.
 */
public interface ConsentRecorder {

    /**
     * @param versionText identificador de la versión del texto aceptado (máx. 50 caracteres)
     * @throws IllegalStateException si no hay una transacción activa
     */
    void recordAuthenticatedAcceptance(UUID guardianId, UUID playerId, ConsentKind kind, String versionText);
}
