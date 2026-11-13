package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.UploadVersion;
import co.edu.unipamplona.canchatrack.documents.domain.DocumentRules;
import co.edu.unipamplona.canchatrack.documents.domain.FieldError;
import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Documentos de un jugador: carga de versiones, consulta y descarga (RF-006 a RF-009, RN-05, RN-17).
 *
 * <ul>
 *   <li>Carga: el acudiente vinculado, solo con ventana habilitada o ante vencimiento (la primera versión siempre); la
 *       coordinación puede cargar en cualquier momento. Cada carga es una versión nueva: nada se reemplaza ni se borra.</li>
 *   <li>Consulta: los documentos de salud solo los ven la coordinación, el entrenador titular de la categoría del jugador
 *       y su acudiente; el comité ve únicamente los demás. Cada consulta de un documento de salud queda auditada.</li>
 *   <li>Quien no puede ver al jugador recibe 404 (no se revela que exista); quien lo ve pero no el documento, 403.</li>
 * </ul>
 */
@Service
public class PlayerDocumentService {

    static final String ACTION_UPLOADED = "DOCUMENT_VERSION_UPLOADED";
    static final String ACTION_HEALTH_VIEWED = "DOCUMENT_HEALTH_VIEWED";
    private static final String ENTITY = "document_version";

    private final DocumentStore store;
    private final FileService files;
    private final PlayerAccessPolicy access;
    private final AuditWriter audit;
    private final DocumentSettings settings;
    private final Clock clock;

    public PlayerDocumentService(DocumentStore store, FileService files, PlayerAccessPolicy access, AuditWriter audit,
                                 DocumentSettings settings, Clock clock) {
        this.store = store;
        this.files = files;
        this.access = access;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /** Un tipo de documento frente a un jugador. {@code uploadAllowed}: ¿puede quien consulta cargar una versión ahora? */
    public record PlayerDocument(DocumentTypeInfo type, DocumentVersionInfo current, DocumentRules.Status status,
                                 boolean uploadAllowed) {
    }

    public record Download(DocumentVersionInfo version, byte[] content) {
    }

    /** Estado de cada tipo de documento activo que el usuario puede ver. */
    @Transactional(readOnly = true)
    public List<PlayerDocument> list(DocumentActor actor, UUID playerId) {
        requireVisiblePlayer(actor, playerId);
        LocalDate today = today();
        var current = new java.util.HashMap<UUID, DocumentVersionInfo>();
        store.findCurrentOfPlayer(playerId).forEach(v -> current.put(v.documentTypeId(), v));
        List<PlayerDocument> result = new ArrayList<>();
        for (DocumentTypeInfo type : store.findTypes(true)) {
            if (!canViewType(actor, playerId, type)) {
                continue;
            }
            DocumentVersionInfo version = current.get(type.id());
            DocumentRules.Status status = DocumentRules.statusOf(version != null,
                    version == null ? null : version.expiresOn(), today);
            result.add(new PlayerDocument(type, version, status, canUpload(actor, playerId, type, version, today)));
        }
        return result;
    }

    /** Historial completo de un tipo de documento, la versión más reciente primero (RF-008). */
    @Transactional
    public List<DocumentVersionInfo> versions(DocumentActor actor, UUID playerId, UUID documentTypeId) {
        requireVisiblePlayer(actor, playerId);
        DocumentTypeInfo type = store.findType(documentTypeId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El tipo de documento no existe."));
        requireViewable(actor, playerId, type);
        List<DocumentVersionInfo> versions = store.findVersions(playerId, documentTypeId);
        if (type.containsHealthData() && !versions.isEmpty()) {
            auditHealthView(actor, playerId, type, versions.get(0).id(), "history");
        }
        return versions;
    }

    @Transactional
    public Download download(DocumentActor actor, UUID playerId, UUID versionId) {
        requireVisiblePlayer(actor, playerId);
        DocumentVersionInfo version = store.findVersion(versionId).filter(v -> v.playerId().equals(playerId))
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "La versión del documento no existe."));
        DocumentTypeInfo type = store.findType(version.documentTypeId()).orElseThrow();
        requireViewable(actor, playerId, type);
        if (type.containsHealthData()) {
            auditHealthView(actor, playerId, type, version.id(), "download");
        }
        return new Download(version, files.load(version.fileId()).content());
    }

    @Transactional
    public DocumentVersionInfo upload(DocumentActor actor, UploadVersion command) {
        UUID playerId = command.playerId();
        if (!store.playerExists(playerId)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe.");
        }
        requireVisiblePlayer(actor, playerId);
        boolean coordinator = actor.has(DocumentActor.COORDINATOR);
        if (!coordinator && !(actor.has(DocumentActor.GUARDIAN) && access.canAccess(actor.id(), actor.roles(), playerId))) {
            throw new DomainException(ErrorCode.AUTH_FORBIDDEN, "Solo el acudiente del jugador o la coordinación cargan documentos.");
        }

        LocalDate today = today();
        List<FieldError> errors = new ArrayList<>();
        DocumentTypeInfo type = command.documentTypeId() == null ? null : store.findType(command.documentTypeId()).orElse(null);
        if (command.documentTypeId() == null) {
            errors.add(new FieldError("documentTypeId", "El tipo de documento es obligatorio."));
        } else if (type == null || !type.active()) {
            errors.add(new FieldError("documentTypeId", "El tipo de documento no existe o no está activo."));
        }
        LocalDate expiry = null;
        if (type != null && type.active()) {
            expiry = DocumentRules.resolveExpiry(type.requiresExpiry(), type.validityDays(), command.issuedOn(), command.expiresOn());
            errors.addAll(DocumentRules.versionViolations(type.requiresExpiry(), command.issuedOn(), expiry, command.notes(), today));
            errors.addAll(fileErrors(type, command));
        }
        ValidationFailure.throwIfAny(errors);

        // Serializa las cargas del mismo jugador: la numeración de versiones y la regla de ventana se evalúan sin carreras.
        store.lockPlayer(playerId);
        DocumentVersionInfo currentVersion = store.findCurrent(playerId, type.id()).orElse(null);
        if (!coordinator) {
            boolean windowOpen = store.hasOpenWindow(type.id(), playerId, clock.instant());
            if (!DocumentRules.guardianMayUpload(currentVersion != null, currentVersion == null ? null : currentVersion.expiresOn(),
                    windowOpen, today)) {
                throw new DomainException(ErrorCode.DOCUMENT_UPLOAD_NOT_ALLOWED,
                        "No hay una ventana habilitada ni un vencimiento próximo para cargar «" + type.name()
                                + "»; solicite a la coordinación que habilite la carga.");
            }
        }

        // El archivo se guarda al final: si algo anterior falla no queda un archivo huérfano en el almacenamiento.
        StoredFile stored = files.upload(actor.id(), command.fileName(), command.contentType(), command.content());
        String notes = command.notes() == null || command.notes().isBlank() ? null : command.notes().trim();
        DocumentVersionInfo version = store.insertVersion(playerId, type.id(), stored.id(), command.issuedOn(), expiry, notes, actor.id());
        audit.write(AuditRecord.builder(ACTION_UPLOADED, ENTITY)
                .actor(actor.id(), actor.auditRole())
                .entity(version.id())
                .after(Json.of("playerId", playerId, "documentType", type.code(), "versionNumber", version.versionNumber(),
                        "expiresOn", version.expiresOn()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return version;
    }

    // ---------- reglas de acceso ----------

    private void requireVisiblePlayer(DocumentActor actor, UUID playerId) {
        if (!store.playerExists(playerId) || !canSeePlayer(actor, playerId)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe.");
        }
    }

    private boolean canSeePlayer(DocumentActor actor, UUID playerId) {
        return access.canAccess(actor.id(), actor.roles(), playerId) || isHeadCoach(actor, playerId);
    }

    private boolean isHeadCoach(DocumentActor actor, UUID playerId) {
        return actor.has(DocumentActor.COACH) && store.isHeadCoachOf(actor.id(), playerId);
    }

    private boolean canViewType(DocumentActor actor, UUID playerId, DocumentTypeInfo type) {
        if (actor.has(DocumentActor.COORDINATOR) || isHeadCoach(actor, playerId)) {
            return true;
        }
        if (actor.has(DocumentActor.GUARDIAN) && access.canAccess(actor.id(), actor.roles(), playerId)) {
            return true;
        }
        return actor.has(DocumentActor.COMMITTEE) && !type.containsHealthData();
    }

    private void requireViewable(DocumentActor actor, UUID playerId, DocumentTypeInfo type) {
        if (!canViewType(actor, playerId, type)) {
            throw new DomainException(ErrorCode.AUTH_FORBIDDEN, "No tiene permiso para consultar este documento.");
        }
    }

    private boolean canUpload(DocumentActor actor, UUID playerId, DocumentTypeInfo type, DocumentVersionInfo current, LocalDate today) {
        if (actor.has(DocumentActor.COORDINATOR)) {
            return true;
        }
        if (!(actor.has(DocumentActor.GUARDIAN) && access.canAccess(actor.id(), actor.roles(), playerId))) {
            return false;
        }
        return DocumentRules.guardianMayUpload(current != null, current == null ? null : current.expiresOn(),
                store.hasOpenWindow(type.id(), playerId, clock.instant()), today);
    }

    private void auditHealthView(DocumentActor actor, UUID playerId, DocumentTypeInfo type, UUID versionId, String how) {
        audit.write(AuditRecord.builder(ACTION_HEALTH_VIEWED, ENTITY)
                .actor(actor.id(), actor.auditRole())
                .entity(versionId)
                .after(Json.of("playerId", playerId, "documentType", type.code(), "access", how))
                .origin(actor.ip(), actor.userAgent())
                .build());
    }

    private List<FieldError> fileErrors(DocumentTypeInfo type, UploadVersion command) {
        List<FieldError> errors = new ArrayList<>();
        if (command.content() == null || command.content().length == 0) {
            errors.add(new FieldError("file", "El archivo es obligatorio y no puede estar vacío."));
            return errors;
        }
        errors.addAll(DocumentRules.fileViolations("file", command.content().length, command.contentType(), type.maxSizeBytes(),
                settings.maxFileSizeBytes(), type.allowedContentTypes()));
        return errors;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(settings.zone()));
    }
}
