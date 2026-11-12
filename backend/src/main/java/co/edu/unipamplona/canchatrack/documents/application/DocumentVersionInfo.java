package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Una versión inmutable de un documento (RF-007, RF-008) con los metadatos de su archivo. */
public record DocumentVersionInfo(UUID id, UUID playerId, UUID documentTypeId, int versionNumber, UUID fileId,
                                  String fileName, String contentType, long sizeBytes, LocalDate issuedOn,
                                  LocalDate expiresOn, String notes, UUID uploadedBy, Instant uploadedAt) {
}
