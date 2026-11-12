package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Datos de entrada de los casos de uso de documentos. */
public final class DocumentCommands {

    private DocumentCommands() {
    }

    public record CreateType(String code, String name, boolean mandatory, boolean requiresExpiry, Integer validityDays,
                             boolean containsHealthData, Long maxSizeBytes, List<String> allowedContentTypes) {
    }

    /** Campos nulos = sin cambio. El código y el carácter de salud de un tipo no se modifican: ya hay versiones que dependen de ellos. */
    public record UpdateType(String name, Boolean mandatory, Boolean requiresExpiry, Integer validityDays,
                             Long maxSizeBytes, List<String> allowedContentTypes, Boolean active) {
    }

    public record OpenWindow(UUID documentTypeId, UUID playerId, Instant opensAt, Instant closesAt, String reason) {
    }

    public record UploadVersion(UUID playerId, UUID documentTypeId, String fileName, String contentType, byte[] content,
                                LocalDate issuedOn, LocalDate expiresOn, String notes) {
    }
}
