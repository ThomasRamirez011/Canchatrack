package co.edu.unipamplona.canchatrack.documents.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.documents.domain.FileRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Carga y descarga de archivos (soporte del acta del comité, comprobantes de pago y, más adelante, documentos de
 * inscripción). Cualquier usuario autenticado puede subir; solo quien lo subió, el coordinador o el comité pueden
 * descargarlo. Un archivo que no existe o que no puede ver responde igual: 404.
 */
@Service
public class FileService {

    private static final Set<String> READERS = Set.of("COORDINATOR", "COMMITTEE");

    private final StoredFileStore files;
    private final FileContentStorage storage;
    private final FileSettings settings;

    public FileService(StoredFileStore files, FileContentStorage storage, FileSettings settings) {
        this.files = files;
        this.storage = storage;
        this.settings = settings;
    }

    @Transactional
    public StoredFile upload(UUID uploaderId, String originalName, String contentType, byte[] content) {
        long size = content == null ? 0 : content.length;
        byte[] head = content == null ? new byte[0] : Arrays.copyOf(content, (int) Math.min(size, 16));
        List<String> problems = FileRules.violations(originalName, contentType, size, settings.maxSizeBytes(), head);
        if (!problems.isEmpty()) {
            throw new DomainException(ErrorCode.FILE_REJECTED, String.join(" ", problems));
        }
        LocalDate today = LocalDate.now();
        String key = "%d/%02d/%s".formatted(today.getYear(), today.getMonthValue(), UUID.randomUUID());
        storage.write(key, content);
        return files.insert(FileRules.sanitizeName(originalName), FileRules.normalizeContentType(contentType), size, key,
                sha256(content), uploaderId);
    }

    /** Metadatos y contenido, solo si el usuario puede verlo. */
    @Transactional(readOnly = true)
    public Download download(UUID userId, Set<String> roles, UUID fileId) {
        StoredFile file = files.find(fileId).orElseThrow(FileService::notFound);
        boolean allowed = userId.equals(file.uploadedBy()) || roles.stream().anyMatch(READERS::contains);
        if (!allowed) {
            throw notFound();
        }
        return new Download(file, storage.read(file.storageKey()));
    }

    public record Download(StoredFile file, byte[] content) {
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El archivo no existe.");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
