package co.edu.unipamplona.canchatrack.documents.application;

import java.util.UUID;

/** Puerto de salida: archivos registrados en {@code stored_files}. */
public interface StoredFileCatalog {

    boolean exists(UUID fileId);
}
