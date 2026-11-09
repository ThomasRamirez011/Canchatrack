package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.documents.application.FileContentStorage;

/**
 * Almacenamiento en disco local: sirve para desarrollo y pruebas. En producción se sustituye por un almacén de objetos
 * (mismo puerto). La clave nunca sale del directorio raíz: se normaliza y se comprueba antes de leer o escribir.
 */
@Component
public class FileSystemContentStorage implements FileContentStorage {

    private final Path root;

    public FileSystemContentStorage(@Value("${canchatrack.files.directory:./data/files}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void write(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el archivo", e);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo", e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("clave de almacenamiento fuera del directorio raíz");
        }
        return path;
    }
}
