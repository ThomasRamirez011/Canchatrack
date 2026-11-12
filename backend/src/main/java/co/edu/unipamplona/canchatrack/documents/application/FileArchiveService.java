package co.edu.unipamplona.canchatrack.documents.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.documents.api.FileArchive;

/** Implementa la fachada reutilizando la subida de archivos: mismas reglas y mismo almacenamiento. */
@Service
public class FileArchiveService implements FileArchive {

    private final FileService files;

    public FileArchiveService(FileService files) {
        this.files = files;
    }

    @Override
    public ArchivedFile store(UUID ownerId, String fileName, String contentType, byte[] content) {
        StoredFile stored = files.upload(ownerId, fileName, contentType, content);
        return new ArchivedFile(stored.id(), stored.sha256());
    }
}
