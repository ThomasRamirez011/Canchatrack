package co.edu.unipamplona.canchatrack.documents.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.documents.api.StoredFileDirectory;

@Service
public class StoredFileDirectoryService implements StoredFileDirectory {

    private final StoredFileCatalog files;

    public StoredFileDirectoryService(StoredFileCatalog files) {
        this.files = files;
    }

    @Override
    public boolean exists(UUID fileId) {
        return fileId != null && files.exists(fileId);
    }
}
