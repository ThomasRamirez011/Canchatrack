package co.edu.unipamplona.canchatrack.documents.application;

import java.time.LocalDate;
import java.util.UUID;

/** Versión vigente de un documento que vence pronto, con lo necesario para avisar sin leer su contenido. */
public record ExpiringDocument(UUID versionId, UUID playerId, String playerCode, String playerName, String documentName,
                               LocalDate expiresOn, UUID headCoachId) {
}
