package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de salida de tipos, ventanas y versiones. Las escrituras exigen una transacción activa. Las versiones solo se
 * insertan: la base rechaza cualquier modificación o borrado (RF-008).
 */
public interface DocumentStore {

    List<DocumentTypeInfo> findTypes(boolean activeOnly);

    Optional<DocumentTypeInfo> findType(UUID id);

    /** @throws DuplicateDocumentTypeException si el código ya existe */
    DocumentTypeInfo insertType(String code, String name, boolean mandatory, boolean requiresExpiry, Integer validityDays,
                                boolean containsHealthData, Long maxSizeBytes, List<String> allowedContentTypes);

    void updateType(UUID id, String name, boolean mandatory, boolean requiresExpiry, Integer validityDays, Long maxSizeBytes,
                    List<String> allowedContentTypes, boolean active);

    boolean playerExists(UUID playerId);

    /** Bloquea la fila del jugador: serializa las cargas del mismo jugador para numerar las versiones sin colisión. */
    boolean lockPlayer(UUID playerId);

    DocumentWindowInfo insertWindow(UUID documentTypeId, UUID playerId, Instant opensAt, Instant closesAt, String reason, UUID openedBy);

    /** Ventanas del tipo (y del jugador o generales, si se indica jugador), las más recientes primero. */
    List<DocumentWindowInfo> findWindows(UUID documentTypeId, UUID playerId);

    /** ¿Hay una ventana abierta en el instante, general o del jugador, para el tipo? */
    boolean hasOpenWindow(UUID documentTypeId, UUID playerId, Instant at);

    Optional<DocumentVersionInfo> findCurrent(UUID playerId, UUID documentTypeId);

    List<DocumentVersionInfo> findCurrentOfPlayer(UUID playerId);

    /** Todas las versiones, la más reciente primero. */
    List<DocumentVersionInfo> findVersions(UUID playerId, UUID documentTypeId);

    Optional<DocumentVersionInfo> findVersion(UUID versionId);

    /** Inserta la versión siguiente (máximo + 1). Quien llama ya bloqueó al jugador. */
    DocumentVersionInfo insertVersion(UUID playerId, UUID documentTypeId, UUID fileId, LocalDate issuedOn, LocalDate expiresOn,
                                      String notes, UUID uploadedBy);

    /** Nombres de los tipos obligatorios y activos sin versión vigente en la fecha. */
    List<String> findMissingMandatory(UUID playerId, LocalDate onDate);

    /** Versiones vigentes de tipos que vencen entre la fecha y la fecha más {@code withinDays}, de jugadores en curso. */
    List<ExpiringDocument> findExpiring(LocalDate from, int withinDays);

    /** ¿Es el usuario el entrenador titular de la asignación vigente del jugador? */
    boolean isHeadCoachOf(UUID userId, UUID playerId);
}
