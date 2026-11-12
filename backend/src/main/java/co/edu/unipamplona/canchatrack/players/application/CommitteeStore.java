package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Puerto de salida: decisiones del comité. Todas las escrituras exigen una transacción activa. */
public interface CommitteeStore {

    /** Bloquea el expediente hasta el fin de la transacción: serializa decisiones concurrentes sobre el mismo jugador. */
    Optional<PlayerRecord> lockPlayer(UUID playerId);

    /** Fecha de hoy en la zona del club según el reloj de la base de datos. */
    LocalDate today(ZoneId zone);

    /** Cierra (valid_to = ahora) la categoría y el panel vigentes del jugador, si los hay. */
    void closeCurrentAssignment(UUID playerId);

    /**
     * @param decision {@code ASSIGNED} o {@code REJECTED}
     * @throws DuplicateMinuteException si ya hay una decisión de ese jugador en un acta con ese número
     */
    UUID insertMinute(UUID playerId, MinuteData minute, String decision, String reason, UUID createdBy);

    void insertCategoryAssignment(UUID playerId, UUID categoryId, UUID headCoachId, UUID minuteId,
                                  String capacityExceptionReason, UUID createdBy);

    void insertEvaluatorAssignment(UUID playerId, UUID evaluatorId, UUID minuteId, UUID createdBy);

    /**
     * Cambia el estado solo si el expediente sigue pendiente de decisión (SUBMITTED o COMMITTEE_PENDING) y avanza su
     * versión optimista.
     *
     * @return {@code false} si el estado ya no lo permite
     */
    boolean decide(UUID playerId, PlayerStatus target);

    Optional<AssignmentView> findCurrentAssignment(UUID playerId);
}
