package co.edu.unipamplona.canchatrack.documents.application;

import java.util.List;
import java.util.UUID;

/** Tipo de documento del catálogo configurable (D-04). {@code allowedContentTypes} vacío = los formatos generales del sistema. */
public record DocumentTypeInfo(UUID id, String code, String name, boolean mandatory, boolean requiresExpiry,
                               Integer validityDays, boolean containsHealthData, Long maxSizeBytes,
                               List<String> allowedContentTypes, boolean active) {

    public DocumentTypeInfo {
        allowedContentTypes = allowedContentTypes == null ? List.of() : List.copyOf(allowedContentTypes);
    }
}
