package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

/** Puerto de salida: expedientes de jugadores y su vínculo con acudientes. Las escrituras exigen transacción. */
public interface PlayerStore {

    /** Bloquea la fila para serializar radicaciones concurrentes del mismo jugador. */
    Optional<PlayerRecord> findByDocumentForUpdate(IdDocumentType type, String normalizedNumber);

    /** @throws DuplicatePlayerDocumentException si el documento ya existe */
    PlayerRecord insert(String firstName, String lastName, IdDocumentType type, String normalizedNumber,
                        LocalDate birthDate, UUID createdBy);

    /** Vuelve el expediente a "inscripción radicada" y avanza su versión optimista. */
    void markRefiled(UUID playerId);

    boolean isLinkedGuardian(UUID playerId, UUID guardianId);

    void linkGuardian(UUID playerId, UUID guardianId, String relationship, boolean primary, UUID createdBy);

    Optional<PlayerDetail> findDetail(UUID playerId);

    /** Una página del listado de jugadores que {@code filter} permite ver. */
    PageResult<PlayerSummary> list(PlayerFilter filter, int page, int size);
}
