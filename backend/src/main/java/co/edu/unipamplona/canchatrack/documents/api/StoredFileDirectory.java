package co.edu.unipamplona.canchatrack.documents.api;

import java.util.UUID;

/** Fachada pública: existencia de archivos almacenados, para validar referencias desde otros módulos (p. ej. el acta). */
public interface StoredFileDirectory {

    boolean exists(UUID fileId);
}
