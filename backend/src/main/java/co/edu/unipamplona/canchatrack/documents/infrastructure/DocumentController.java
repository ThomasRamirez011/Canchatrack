package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.edu.unipamplona.canchatrack.documents.application.DocumentActor;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCatalogService;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.CreateType;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.OpenWindow;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.UpdateType;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.UploadVersion;
import co.edu.unipamplona.canchatrack.documents.application.PlayerDocumentService;
import co.edu.unipamplona.canchatrack.documents.infrastructure.DocumentDtos.CreateDocumentTypeRequest;
import co.edu.unipamplona.canchatrack.documents.infrastructure.DocumentDtos.DocumentTypeResponse;
import co.edu.unipamplona.canchatrack.documents.infrastructure.DocumentDtos.DocumentVersionResponse;
import co.edu.unipamplona.canchatrack.documents.infrastructure.DocumentDtos.DocumentWindowResponse;
import co.edu.unipamplona.canchatrack.documents.infrastructure.DocumentDtos.OpenDocumentWindowRequest;
import co.edu.unipamplona.canchatrack.documents.infrastructure.DocumentDtos.PlayerDocumentResponse;
import co.edu.unipamplona.canchatrack.documents.infrastructure.DocumentDtos.UpdateDocumentTypeRequest;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Catálogo de documentos, ventanas de carga y documentos de cada jugador (CU-13; RF-006 a RF-010, RN-05, RN-17). Quién ve
 * qué lo decide {@link PlayerDocumentService}; aquí solo se aplica el rol mínimo de cada operación.
 */
@RestController
@RequestMapping(ApiPaths.V1)
public class DocumentController {

    private static final String ANY_ROLE_WITH_DOCUMENTS = "hasAnyRole('COORDINATOR', 'COMMITTEE', 'COACH', 'GUARDIAN')";

    private final DocumentCatalogService catalog;
    private final PlayerDocumentService documents;

    public DocumentController(DocumentCatalogService catalog, PlayerDocumentService documents) {
        this.catalog = catalog;
        this.documents = documents;
    }

    // ---------- catálogo ----------

    /** Cualquier usuario autenticado: el acudiente necesita saber qué documentos se exigen. Solo la coordinación ve los inactivos. */
    @GetMapping("/document-types")
    public List<DocumentTypeResponse> listTypes(@RequestParam(defaultValue = "false") boolean includeInactive) {
        boolean coordinator = CurrentUser.require().hasRole(DocumentActor.COORDINATOR);
        return catalog.listTypes(includeInactive && coordinator).stream().map(DocumentTypeResponse::from).toList();
    }

    @PostMapping("/document-types")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentTypeResponse createType(@RequestBody CreateDocumentTypeRequest request, HttpServletRequest http) {
        return DocumentTypeResponse.from(catalog.createType(actor(http), new CreateType(request.code(), request.name(),
                request.mandatory() == null || request.mandatory(), Boolean.TRUE.equals(request.requiresExpiry()),
                request.validityDays(), Boolean.TRUE.equals(request.containsHealthData()), request.maxSizeBytes(),
                request.allowedContentTypes())));
    }

    @PatchMapping("/document-types/{typeId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public DocumentTypeResponse updateType(@PathVariable UUID typeId, @RequestBody UpdateDocumentTypeRequest request,
                                           HttpServletRequest http) {
        return DocumentTypeResponse.from(catalog.updateType(actor(http), typeId, new UpdateType(request.name(),
                request.mandatory(), request.requiresExpiry(), request.validityDays(), request.maxSizeBytes(),
                request.allowedContentTypes(), request.active())));
    }

    // ---------- ventanas ----------

    @PostMapping("/document-windows")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentWindowResponse openWindow(@RequestBody OpenDocumentWindowRequest request, HttpServletRequest http) {
        return DocumentWindowResponse.from(catalog.openWindow(actor(http), new OpenWindow(request.documentTypeId(),
                request.playerId(), request.opensAt(), request.closesAt(), request.reason())));
    }

    @GetMapping("/document-windows")
    @PreAuthorize("hasRole('COORDINATOR')")
    public List<DocumentWindowResponse> listWindows(@RequestParam(required = false) UUID documentTypeId,
                                                    @RequestParam(required = false) UUID playerId) {
        return catalog.listWindows(documentTypeId, playerId).stream().map(DocumentWindowResponse::from).toList();
    }

    // ---------- documentos del jugador ----------

    @GetMapping("/players/{playerId}/documents")
    @PreAuthorize(ANY_ROLE_WITH_DOCUMENTS)
    public List<PlayerDocumentResponse> list(@PathVariable UUID playerId, HttpServletRequest http) {
        return documents.list(actor(http), playerId).stream().map(PlayerDocumentResponse::from).toList();
    }

    @PostMapping(value = "/players/{playerId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('COORDINATOR', 'GUARDIAN')")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentVersionResponse upload(@PathVariable UUID playerId,
                                          @RequestParam(required = false) UUID documentTypeId,
                                          @RequestParam(required = false) String issuedOn,
                                          @RequestParam(required = false) String expiresOn,
                                          @RequestParam(required = false) String notes,
                                          @RequestParam(name = "file", required = false) MultipartFile file,
                                          HttpServletRequest http) {
        List<Map<String, String>> errors = new ArrayList<>();
        LocalDate issued = date("issuedOn", issuedOn, errors);
        LocalDate expires = date("expiresOn", expiresOn, errors);
        if (file == null) {
            errors.add(Map.of("field", "file", "message", "El archivo es obligatorio."));
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", errors));
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new DomainException(ErrorCode.FILE_REJECTED, "No fue posible leer el archivo enviado.");
        }
        return DocumentVersionResponse.from(documents.upload(actor(http), new UploadVersion(playerId, documentTypeId,
                file.getOriginalFilename(), file.getContentType(), content, issued, expires, notes)));
    }

    @GetMapping("/players/{playerId}/document-types/{typeId}/versions")
    @PreAuthorize(ANY_ROLE_WITH_DOCUMENTS)
    public List<DocumentVersionResponse> versions(@PathVariable UUID playerId, @PathVariable UUID typeId, HttpServletRequest http) {
        return documents.versions(actor(http), playerId, typeId).stream().map(DocumentVersionResponse::from).toList();
    }

    @GetMapping("/players/{playerId}/documents/versions/{versionId}/file")
    @PreAuthorize(ANY_ROLE_WITH_DOCUMENTS)
    public ResponseEntity<byte[]> download(@PathVariable UUID playerId, @PathVariable UUID versionId, HttpServletRequest http) {
        PlayerDocumentService.Download download = documents.download(actor(http), playerId, versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.version().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.version().fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(download.content());
    }

    // ---------- utilidades ----------

    private static LocalDate date(String field, String value, List<Map<String, String>> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            errors.add(Map.of("field", field, "message", "Use el formato AAAA-MM-DD."));
            return null;
        }
    }

    private static DocumentActor actor(HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.require();
        return new DocumentActor(user.id(), user.roles(), remoteAddress(request), request.getHeader("User-Agent"));
    }

    /** {@code getRemoteAddr()} devuelve un literal de IP: {@code getByName} no hace consultas DNS. */
    private static InetAddress remoteAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(remote);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
