package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.CreateType;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.OpenWindow;
import co.edu.unipamplona.canchatrack.documents.application.DocumentCommands.UpdateType;
import co.edu.unipamplona.canchatrack.documents.domain.DocumentRules;
import co.edu.unipamplona.canchatrack.documents.domain.FieldError;
import co.edu.unipamplona.canchatrack.documents.domain.FileRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Catálogo de documentos y ventanas de carga, a cargo de la coordinación (D-04, RN-05). Un tipo no se borra: se desactiva,
 * porque ya puede tener versiones; su código y su carácter de «dato de salud» tampoco cambian después de creado.
 */
@Service
public class DocumentCatalogService {

    static final String ACTION_TYPE_CREATED = "DOCUMENT_TYPE_CREATED";
    static final String ACTION_TYPE_UPDATED = "DOCUMENT_TYPE_UPDATED";
    static final String ACTION_WINDOW_OPENED = "DOCUMENT_WINDOW_OPENED";

    private final DocumentStore store;
    private final DocumentSettings settings;
    private final AuditWriter audit;
    private final Clock clock;

    public DocumentCatalogService(DocumentStore store, DocumentSettings settings, AuditWriter audit, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DocumentTypeInfo> listTypes(boolean includeInactive) {
        return store.findTypes(!includeInactive);
    }

    @Transactional
    public DocumentTypeInfo createType(DocumentActor actor, CreateType command) {
        List<String> formats = normalizeFormats(command.allowedContentTypes());
        List<FieldError> errors = new ArrayList<>(DocumentRules.codeViolations(command.code()));
        errors.addAll(DocumentRules.typeViolations(command.name(), command.requiresExpiry(), command.validityDays(),
                command.maxSizeBytes(), formats.isEmpty() ? null : formats, FileRules.ALLOWED_TYPES, settings.maxFileSizeBytes()));
        ValidationFailure.throwIfAny(errors);

        String code = command.code().trim();
        DocumentTypeInfo created;
        try {
            created = store.insertType(code, command.name().trim(), command.mandatory(), command.requiresExpiry(),
                    command.validityDays(), command.containsHealthData(), command.maxSizeBytes(), formats);
        } catch (DuplicateDocumentTypeException e) {
            throw ValidationFailure.of("code", "Ya existe un tipo de documento con ese código.");
        }
        audit.write(AuditRecord.builder(ACTION_TYPE_CREATED, "document_type")
                .actor(actor.id(), actor.auditRole())
                .entity(created.id())
                .after(summary(created))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return created;
    }

    @Transactional
    public DocumentTypeInfo updateType(DocumentActor actor, UUID typeId, UpdateType command) {
        DocumentTypeInfo current = store.findType(typeId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El tipo de documento no existe."));
        String name = command.name() == null ? current.name() : command.name();
        boolean mandatory = command.mandatory() == null ? current.mandatory() : command.mandatory();
        boolean requiresExpiry = command.requiresExpiry() == null ? current.requiresExpiry() : command.requiresExpiry();
        Integer validityDays = command.validityDays() != null ? command.validityDays()
                : (requiresExpiry ? current.validityDays() : null);
        Long maxSize = command.maxSizeBytes() == null ? current.maxSizeBytes() : command.maxSizeBytes();
        // Lista vacía en la solicitud = quitar la restricción; ausente = sin cambio.
        List<String> formats = command.allowedContentTypes() == null ? current.allowedContentTypes()
                : normalizeFormats(command.allowedContentTypes());
        boolean active = command.active() == null ? current.active() : command.active();

        List<FieldError> errors = new ArrayList<>(DocumentRules.typeViolations(name, requiresExpiry, validityDays, maxSize,
                formats.isEmpty() ? null : formats, FileRules.ALLOWED_TYPES, settings.maxFileSizeBytes()));
        ValidationFailure.throwIfAny(errors);

        name = name.trim();
        boolean unchanged = name.equals(current.name()) && mandatory == current.mandatory()
                && requiresExpiry == current.requiresExpiry() && java.util.Objects.equals(validityDays, current.validityDays())
                && java.util.Objects.equals(maxSize, current.maxSizeBytes()) && formats.equals(current.allowedContentTypes())
                && active == current.active();
        if (unchanged) {
            return current;
        }
        store.updateType(typeId, name, mandatory, requiresExpiry, validityDays, maxSize, formats, active);
        DocumentTypeInfo updated = store.findType(typeId).orElseThrow();
        audit.write(AuditRecord.builder(ACTION_TYPE_UPDATED, "document_type")
                .actor(actor.id(), actor.auditRole())
                .entity(typeId)
                .before(summary(current))
                .after(summary(updated))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return updated;
    }

    /** Habilita la carga de un tipo (RN-05): para un jugador o, sin jugador, para todos. */
    @Transactional
    public DocumentWindowInfo openWindow(DocumentActor actor, OpenWindow command) {
        List<FieldError> errors = new ArrayList<>(DocumentRules.windowViolations(command.opensAt(), command.closesAt(),
                command.reason(), clock.instant()));
        DocumentTypeInfo type = command.documentTypeId() == null ? null : store.findType(command.documentTypeId()).orElse(null);
        if (command.documentTypeId() == null) {
            errors.add(new FieldError("documentTypeId", "El tipo de documento es obligatorio."));
        } else if (type == null) {
            errors.add(new FieldError("documentTypeId", "El tipo de documento no existe."));
        } else if (!type.active()) {
            errors.add(new FieldError("documentTypeId", "El tipo de documento «" + type.name() + "» no está activo."));
        }
        if (command.playerId() != null && !store.playerExists(command.playerId())) {
            errors.add(new FieldError("playerId", "El jugador no existe."));
        }
        ValidationFailure.throwIfAny(errors);

        String reason = command.reason() == null || command.reason().isBlank() ? null : command.reason().trim();
        DocumentWindowInfo window = store.insertWindow(type.id(), command.playerId(), command.opensAt(), command.closesAt(),
                reason, actor.id());
        audit.write(AuditRecord.builder(ACTION_WINDOW_OPENED, "document_window")
                .actor(actor.id(), actor.auditRole())
                .entity(window.id())
                .after(Json.of("documentTypeId", type.id(), "playerId", command.playerId(), "opensAt", window.opensAt(),
                        "closesAt", window.closesAt()))
                .reason(reason)
                .origin(actor.ip(), actor.userAgent())
                .build());
        return window;
    }

    @Transactional(readOnly = true)
    public List<DocumentWindowInfo> listWindows(UUID documentTypeId, UUID playerId) {
        return store.findWindows(documentTypeId, playerId);
    }

    private static List<String> normalizeFormats(List<String> formats) {
        if (formats == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(formats.stream().map(FileRules::normalizeContentType).toList()));
    }

    private static String summary(DocumentTypeInfo type) {
        return Json.of("code", type.code(), "name", type.name(), "mandatory", type.mandatory(),
                "requiresExpiry", type.requiresExpiry(), "validityDays", type.validityDays(),
                "containsHealthData", type.containsHealthData(), "maxSizeBytes", type.maxSizeBytes(),
                "allowedContentTypes", String.join(",", type.allowedContentTypes()), "active", type.active());
    }
}
