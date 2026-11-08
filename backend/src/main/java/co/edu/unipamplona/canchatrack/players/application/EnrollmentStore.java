package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;
import java.util.UUID;

/** Puerto de salida: inscripciones. */
public interface EnrollmentStore {

    /**
     * @param exceptionId habilitación excepcional usada, o {@code null}
     * @throws DuplicateEnrollmentException si el jugador ya está inscrito en la convocatoria
     */
    EnrollmentRecord insert(UUID playerId, UUID callId, UUID guardianId, UUID exceptionId, UUID createdBy);

    List<EnrollmentSummary> findByPlayer(UUID playerId);
}
