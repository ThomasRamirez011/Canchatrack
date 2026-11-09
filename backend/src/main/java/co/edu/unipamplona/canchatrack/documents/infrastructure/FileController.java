package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.edu.unipamplona.canchatrack.documents.application.FileService;
import co.edu.unipamplona.canchatrack.documents.application.StoredFile;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;

/** Carga de archivos (multipart) y descarga. Cualquier usuario autenticado sube; ver {@link FileService} para quién descarga. */
@RestController
@RequestMapping(ApiPaths.V1 + "/files")
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    public record FileResponse(UUID id, String originalName, String contentType, long sizeBytes, String sha256,
                               Instant uploadedAt) {

        static FileResponse from(StoredFile file) {
            return new FileResponse(file.id(), file.originalName(), file.contentType(), file.sizeBytes(), file.sha256(),
                    file.uploadedAt());
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponse upload(@RequestParam("file") MultipartFile file) {
        AuthenticatedUser user = CurrentUser.require();
        try {
            return FileResponse.from(files.upload(user.id(), file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        } catch (IOException e) {
            throw new DomainException(ErrorCode.FILE_REJECTED, "No fue posible leer el archivo enviado.");
        }
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID fileId) {
        AuthenticatedUser user = CurrentUser.require();
        FileService.Download download = files.download(user.id(), user.roles(), fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.file().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.file().originalName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.content());
    }
}
