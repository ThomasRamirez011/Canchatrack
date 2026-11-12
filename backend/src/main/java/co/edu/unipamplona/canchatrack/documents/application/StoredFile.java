package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Instant;
import java.util.UUID;

/** Metadatos de un archivo almacenado ({@code stored_files}); el contenido vive en el almacenamiento de archivos. */
public record StoredFile(UUID id, String originalName, String contentType, long sizeBytes, String storageKey,
                         String sha256, UUID uploadedBy, Instant uploadedAt) {
}
