package co.edu.unipamplona.canchatrack.identity.application;

import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

/** Puerto de salida: fichas de acudiente (tabla {@code guardians}). La escritura exige una transacción activa. */
public interface GuardianRegistry {

    Optional<UUID> findGuardianId(UUID userId);

    boolean existsByDocument(IdDocumentType type, String normalizedNumber);

    /** @throws DuplicateGuardianDocumentException si el documento ya pertenece a otro acudiente */
    UUID create(UUID userId, IdDocumentType type, String normalizedNumber, String phone);
}
