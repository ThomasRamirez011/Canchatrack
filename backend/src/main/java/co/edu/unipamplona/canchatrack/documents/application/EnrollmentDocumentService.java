package co.edu.unipamplona.canchatrack.documents.application;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.api.EnrollmentDocuments;
import co.edu.unipamplona.canchatrack.documents.domain.DocumentRules;
import co.edu.unipamplona.canchatrack.documents.domain.FieldError;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Documentos iniciales de la radicación (DAD CU-02, paso 2 a 4; RF-006, RF-007).
 *
 * <ul>
 *   <li>El acudiente adjunta archivos que él mismo subió con {@code POST /files}: un archivo ajeno o inexistente responde
 *       igual, para no revelar qué archivos existen, y un archivo ya usado por otro documento no se reutiliza.</li>
 *   <li>Cada adjunto es la versión siguiente de su tipo. Un jugador nuevo no tiene versiones, así que la primera siempre se
 *       admite; si se reutiliza un expediente que ya tiene una versión de ese tipo, rige la regla de ventana o vencimiento
 *       (RN-05) igual que en {@code POST /players/{id}/documents}.</li>
 *   <li>Ningún tipo es obligatorio para radicar: lo que falte se informa y se carga después (la activación lo exige).</li>
 * </ul>
 */
@Service
public class EnrollmentDocumentService implements EnrollmentDocuments {

    /** Tope de documentos por radicación (el contrato OpenAPI lo repite en {@code maxItems}). */
    public static final int MAX_ATTACHMENTS = 20;
    static final String ACTION_UPLOADED = "DOCUMENT_VERSION_UPLOADED";
    private static final String ENTITY = "document_version";

    private final DocumentStore store;
    private final StoredFileStore files;
    private final AuditWriter audit;
    private final DocumentSettings settings;
    private final Clock clock;

    public EnrollmentDocumentService(DocumentStore store, StoredFileStore files, AuditWriter audit, DocumentSettings settings,
                                     Clock clock) {
        this.store = store;
        this.files = files;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Problem> check(UUID guardianUserId, List<Attachment> attachments) {
        return validate(guardianUserId, attachments).stream().map(e -> new Problem(e.field(), e.message())).toList();
    }

    @Override
    @Transactional
    public Attached attach(UUID guardianUserId, UUID playerId, List<Attachment> attachments, InetAddress ip, String userAgent) {
        List<Attachment> list = attachments == null ? List.of() : attachments;
        LocalDate today = today();
        if (!list.isEmpty()) {
            ValidationFailure.throwIfAny(validate(guardianUserId, list));

            // Serializa las cargas del jugador (numeración de versiones) y los archivos reclamados (orden fijo: sin interbloqueo).
            store.lockPlayer(playerId);
            list.stream().map(Attachment::fileId).sorted(Comparator.naturalOrder()).forEach(store::lockFile);
            for (int i = 0; i < list.size(); i++) {
                if (store.isFileAttached(list.get(i).fileId())) {
                    throw ValidationFailure.of(field(i, "fileId"), "El archivo ya está asociado a otro documento.");
                }
            }

            Instant now = clock.instant();
            for (Attachment attachment : list) {
                DocumentTypeInfo type = store.findType(attachment.documentTypeId()).orElseThrow();
                DocumentVersionInfo current = store.findCurrent(playerId, type.id()).orElse(null);
                boolean windowOpen = current != null && store.hasOpenWindow(type.id(), playerId, now);
                if (!DocumentRules.guardianMayUpload(current != null, current == null ? null : current.expiresOn(), windowOpen, today)) {
                    throw new DomainException(ErrorCode.DOCUMENT_UPLOAD_NOT_ALLOWED,
                            "No hay una ventana habilitada ni un vencimiento próximo para cargar «" + type.name()
                                    + "»; solicite a la coordinación que habilite la carga.");
                }
                LocalDate expiry = DocumentRules.resolveExpiry(type.requiresExpiry(), type.validityDays(), attachment.issuedOn(),
                        attachment.expiresOn());
                String notes = attachment.notes() == null || attachment.notes().isBlank() ? null : attachment.notes().trim();
                DocumentVersionInfo version = store.insertVersion(playerId, type.id(), attachment.fileId(), attachment.issuedOn(),
                        expiry, notes, guardianUserId);
                audit.write(AuditRecord.builder(ACTION_UPLOADED, ENTITY)
                        .actor(guardianUserId, "GUARDIAN")
                        .entity(version.id())
                        .after(Json.of("playerId", playerId, "documentType", type.code(), "versionNumber", version.versionNumber(),
                                "expiresOn", version.expiresOn(), "source", "enrollment"))
                        .origin(ip, userAgent)
                        .build());
            }
        }
        return new Attached(list.size(), store.findMissingMandatory(playerId, today));
    }

    private List<FieldError> validate(UUID guardianUserId, List<Attachment> attachments) {
        List<FieldError> errors = new ArrayList<>();
        if (attachments == null || attachments.isEmpty()) {
            return errors;
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            errors.add(new FieldError("documents", "Se admiten hasta " + MAX_ATTACHMENTS + " documentos por radicación."));
            return errors;
        }
        LocalDate today = today();
        Set<UUID> typesSeen = new HashSet<>();
        Set<UUID> filesSeen = new HashSet<>();
        for (int i = 0; i < attachments.size(); i++) {
            Attachment attachment = attachments.get(i);
            if (attachment == null) {
                errors.add(new FieldError("documents[" + i + "]", "El documento adjunto es obligatorio."));
                continue;
            }
            DocumentTypeInfo type = null;
            if (attachment.documentTypeId() == null) {
                errors.add(new FieldError(field(i, "documentTypeId"), "El tipo de documento es obligatorio."));
            } else {
                type = store.findType(attachment.documentTypeId()).filter(DocumentTypeInfo::active).orElse(null);
                if (type == null) {
                    errors.add(new FieldError(field(i, "documentTypeId"), "El tipo de documento no existe o no está activo."));
                } else if (!typesSeen.add(type.id())) {
                    errors.add(new FieldError(field(i, "documentTypeId"),
                            "Cada tipo de documento se adjunta una sola vez en la radicación."));
                    type = null;
                }
            }
            StoredFile file = null;
            if (attachment.fileId() == null) {
                errors.add(new FieldError(field(i, "fileId"), "El archivo es obligatorio: súbalo primero con POST /files."));
            } else if (!filesSeen.add(attachment.fileId())) {
                errors.add(new FieldError(field(i, "fileId"), "El mismo archivo no puede respaldar dos documentos."));
            } else {
                file = files.find(attachment.fileId()).filter(f -> guardianUserId.equals(f.uploadedBy())).orElse(null);
                if (file == null) {
                    errors.add(new FieldError(field(i, "fileId"), "El archivo no existe."));
                } else if (store.isFileAttached(file.id())) {
                    errors.add(new FieldError(field(i, "fileId"), "El archivo ya está asociado a otro documento."));
                    file = null;
                }
            }
            if (type != null) {
                LocalDate expiry = DocumentRules.resolveExpiry(type.requiresExpiry(), type.validityDays(), attachment.issuedOn(),
                        attachment.expiresOn());
                for (FieldError e : DocumentRules.versionViolations(type.requiresExpiry(), attachment.issuedOn(), expiry,
                        attachment.notes(), today)) {
                    errors.add(new FieldError(field(i, e.field()), e.message()));
                }
                if (file != null) {
                    for (FieldError e : DocumentRules.fileViolations("fileId", file.sizeBytes(), file.contentType(), type.maxSizeBytes(),
                            settings.maxFileSizeBytes(), type.allowedContentTypes())) {
                        errors.add(new FieldError(field(i, e.field()), e.message()));
                    }
                }
            }
        }
        return errors;
    }

    private static String field(int index, String name) {
        return "documents[" + index + "]." + name;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(settings.zone()));
    }
}
