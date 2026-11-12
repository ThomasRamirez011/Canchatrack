package co.edu.unipamplona.canchatrack.documents.application;

import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: metadatos de archivos. La escritura exige una transacción activa. */
public interface StoredFileStore {

    StoredFile insert(String originalName, String contentType, long sizeBytes, String storageKey, String sha256, UUID uploadedBy);

    Optional<StoredFile> find(UUID fileId);

    /** ¿Es el archivo una versión de un documento de salud? Esos solo se descargan por la vía de documentos (RF-009). */
    boolean isHealthDocument(UUID fileId);
}
