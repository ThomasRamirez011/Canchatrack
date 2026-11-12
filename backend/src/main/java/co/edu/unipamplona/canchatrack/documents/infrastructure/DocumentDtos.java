package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.documents.application.DocumentTypeInfo;
import co.edu.unipamplona.canchatrack.documents.application.DocumentVersionInfo;
import co.edu.unipamplona.canchatrack.documents.application.DocumentWindowInfo;
import co.edu.unipamplona.canchatrack.documents.application.PlayerDocumentService.PlayerDocument;

/**
 * Contratos JSON de documentos y vigencias (ver openapi/canchatrack-v1.yaml). Las solicitudes no llevan Bean Validation:
 * las reglas viven en el dominio para devolver todos los errores juntos.
 */
public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record CreateDocumentTypeRequest(String code, String name, Boolean mandatory, Boolean requiresExpiry,
                                            Integer validityDays, Boolean containsHealthData, Long maxSizeBytes,
                                            List<String> allowedContentTypes) {
    }

    public record UpdateDocumentTypeRequest(String name, Boolean mandatory, Boolean requiresExpiry, Integer validityDays,
                                            Long maxSizeBytes, List<String> allowedContentTypes, Boolean active) {
    }

    public record DocumentTypeResponse(UUID id, String code, String name, boolean mandatory, boolean requiresExpiry,
                                       Integer validityDays, boolean containsHealthData, Long maxSizeBytes,
                                       List<String> allowedContentTypes, boolean active) {

        static DocumentTypeResponse from(DocumentTypeInfo t) {
            return new DocumentTypeResponse(t.id(), t.code(), t.name(), t.mandatory(), t.requiresExpiry(), t.validityDays(),
                    t.containsHealthData(), t.maxSizeBytes(), t.allowedContentTypes(), t.active());
        }
    }

    public record OpenDocumentWindowRequest(UUID documentTypeId, UUID playerId, Instant opensAt, Instant closesAt, String reason) {
    }

    public record DocumentWindowResponse(UUID id, UUID documentTypeId, UUID playerId, Instant opensAt, Instant closesAt,
                                         String reason, UUID openedBy, Instant createdAt) {

        static DocumentWindowResponse from(DocumentWindowInfo w) {
            return new DocumentWindowResponse(w.id(), w.documentTypeId(), w.playerId(), w.opensAt(), w.closesAt(), w.reason(),
                    w.openedBy(), w.createdAt());
        }
    }

    public record DocumentVersionResponse(UUID id, UUID playerId, UUID documentTypeId, int versionNumber, String fileName,
                                          String contentType, long sizeBytes, LocalDate issuedOn, LocalDate expiresOn,
                                          String notes, UUID uploadedBy, Instant uploadedAt) {

        static DocumentVersionResponse from(DocumentVersionInfo v) {
            return new DocumentVersionResponse(v.id(), v.playerId(), v.documentTypeId(), v.versionNumber(), v.fileName(),
                    v.contentType(), v.sizeBytes(), v.issuedOn(), v.expiresOn(), v.notes(), v.uploadedBy(), v.uploadedAt());
        }
    }

    public record PlayerDocumentResponse(DocumentTypeResponse type, DocumentVersionResponse current, String status,
                                         boolean uploadAllowed) {

        static PlayerDocumentResponse from(PlayerDocument d) {
            return new PlayerDocumentResponse(DocumentTypeResponse.from(d.type()),
                    d.current() == null ? null : DocumentVersionResponse.from(d.current()), d.status().name(), d.uploadAllowed());
        }
    }
}
